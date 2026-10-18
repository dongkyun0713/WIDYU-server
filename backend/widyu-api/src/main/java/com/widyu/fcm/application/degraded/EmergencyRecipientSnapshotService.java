package com.widyu.fcm.application.degraded;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.member.repository.EmergencyRecipientRow;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 보호자 토큰 스냅샷을 Redis에 주기적으로 다시 만든다(LLD-0086 5.1).
 *
 * <p>@Transactional을 두지 않는다. DB가 안 될 때 트랜잭션 시작 자체가 예외라 메서드 안의 catch에 닿지 않고,
 * 조회 예외를 잡아도 롤백 전용 표시가 커밋 시점 예외로 다시 나온다. 두 조회는 각자 읽기만 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmergencyRecipientSnapshotService {

    static final String KEY_PREFIX = "fcm:degraded:recipients:";

    private final FamilyMembershipRepository memberships;
    private final SeniorProfileRepository seniors;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final FcmDegradedProperties properties;
    private final PlatformTransactionManager transactionManager;

    /**
     * 주기 갱신과 커밋 후 갱신·삭제의 「DB 조회 → Redis 쓰기」를 직렬화한다. 없으면 주기 갱신이 보호자 제외 전에
     * 읽은 목록을, 커밋 후 갱신이 쓴 뒤에 덮어써 제외한 보호자가 다시 스냅샷에 남는다.
     */
    // ponytail: JVM 안 락이라 앱 인스턴스가 한 대일 때만 맞다. 여러 대가 되면 키별 버전 비교(Lua)로 바꾼다.
    private final ReentrantLock writeLock = new ReentrantLock();

    /** 기동 직후 바로 한 번 돌고 이후 fixedDelay마다 돈다(initialDelay 없음). */
    @Scheduled(fixedDelayString = "${fcm.degraded.snapshot-refresh-ms:300000}")
    public void refreshAll() {
        if (!properties.enabled()) {
            return;
        }
        refresh(null);
    }

    /** 가족 하나만 다시 만든다. 가족 제외·탈퇴 커밋 뒤 {@link #refreshFamilyAfterCommit}이 부른다. */
    public void refreshFamily(Long familyId) {
        if (familyId == null || !properties.enabled()) {
            return;
        }
        refresh(familyId);
    }

    /**
     * 현재 트랜잭션이 커밋된 뒤 그 가족의 스냅샷을 다시 만든다(LLD-0087 5.2). 롤백되면 하지 않는다.
     * afterCommit에서는 끝난 트랜잭션의 자원이 아직 묶여 있어 읽기 전용 새 트랜잭션으로 조회한다.
     * 실패는 경고로 끝내 이미 커밋된 요청을 실패시키지 않는다. 다음 주기 갱신이 다시 만든다.
     */
    public void refreshFamilyAfterCommit(Long familyId) {
        afterCommit(familyId, () -> {
            TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
            readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            readOnly.setReadOnly(true);
            readOnly.executeWithoutResult(status -> refreshFamily(familyId));
        });
    }

    /**
     * 가족이 해체돼 시니어가 가족에서 빠지면 가족 ID로는 그 시니어를 찾을 수 없으므로, 커밋 뒤 키를 바로 지운다.
     * 받을 보호자가 없는 시니어의 스냅샷은 지우는 것이 갱신 결과와 같다.
     */
    public void evictAfterCommit(Long familyId, List<Long> seniorMemberIds) {
        if (seniorMemberIds.isEmpty()) {
            return;
        }
        List<String> keys = seniorMemberIds.stream().map(seniorId -> KEY_PREFIX + seniorId).toList();
        afterCommit(familyId, () -> redis.delete(keys));
    }

    private void afterCommit(Long familyId, Runnable task) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 읽기 트랜잭션을 열기 전에 잡아 DB 커넥션을 쥔 채 주기 갱신을 기다리지 않는다.
                writeLock.lock();
                try {
                    task.run();
                } catch (RuntimeException exception) {
                    log.warn("가족 변경 후 긴급 수신자 스냅샷 갱신 실패: familyId={}, type={}",
                            familyId, exception.getClass().getSimpleName());
                } finally {
                    writeLock.unlock();
                }
            }
        });
    }

    public Optional<EmergencyRecipientSnapshot> find(Long seniorId) {
        String json = redis.opsForValue().get(KEY_PREFIX + seniorId);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, EmergencyRecipientSnapshot.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("긴급 수신자 스냅샷을 읽을 수 없습니다.", exception);
        }
    }

    private void refresh(Long familyId) {
        int seniorCount = 0;
        writeLock.lock();
        try {
            Map<Long, List<EmergencyRecipientRow>> rowsBySenior = memberships.findEmergencyRecipients(familyId)
                    .stream()
                    .collect(Collectors.groupingBy(EmergencyRecipientRow::seniorId));
            List<Long> seniorIds = seniors.findSeniorMemberIds(familyId);
            seniorCount = seniorIds.size();
            for (Long seniorId : seniorIds) {
                List<EmergencyRecipientRow> rows = rowsBySenior.get(seniorId);
                if (rows == null) {
                    // 받을 사람이 없으면 낡은 스냅샷이 남지 않게 지운다.
                    redis.delete(KEY_PREFIX + seniorId);
                    continue;
                }
                redis.opsForValue().set(KEY_PREFIX + seniorId, toJson(rows), properties.snapshotTtl());
            }
            log.debug("긴급 수신자 스냅샷 갱신: familyId={}, seniors={}, withRecipients={}",
                    familyId, seniorCount, rowsBySenior.size());
        } catch (RuntimeException exception) {
            // DB·Redis 어느 쪽 실패든 스케줄러를 죽이지 않고 다음 주기에 다시 만든다.
            log.warn("긴급 수신자 스냅샷 갱신 실패: familyId={}, seniors={}, type={}",
                    familyId, seniorCount, exception.getClass().getSimpleName());
        } finally {
            writeLock.unlock();
        }
    }

    private String toJson(List<EmergencyRecipientRow> rows) {
        EmergencyRecipientRow first = rows.getFirst();
        List<EmergencyRecipientSnapshot.Recipient> recipients = rows.stream()
                .map(row -> new EmergencyRecipientSnapshot.Recipient(row.guardianId(), row.tokenId(), row.token()))
                .toList();
        try {
            return objectMapper.writeValueAsString(
                    new EmergencyRecipientSnapshot(first.seniorName(), first.seniorImage(), recipients));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("긴급 수신자 스냅샷을 쓸 수 없습니다.", exception);
        }
    }
}
