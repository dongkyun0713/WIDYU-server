package com.widyu.fcm.application.degraded;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.member.repository.EmergencyRecipientRow;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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

    /** 기동 직후 바로 한 번 돌고 이후 fixedDelay마다 돈다(initialDelay 없음). */
    @Scheduled(fixedDelayString = "${fcm.degraded.snapshot-refresh-ms:300000}")
    public void refreshAll() {
        if (!properties.enabled()) {
            return;
        }
        refresh(null);
    }

    /** 가족 하나만 다시 만든다. 가족 제외·탈퇴 직후 훅(LLD-0087)이 부른다. */
    public void refreshFamily(Long familyId) {
        if (familyId == null) {
            return;
        }
        refresh(familyId);
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
