package com.widyu.auth.application.guardian.unlink;

import com.widyu.auth.SocialUnlinkTask;
import com.widyu.auth.SocialUnlinkTaskStatus;
import com.widyu.auth.application.guardian.oauth.strategy.SocialLoginStrategyFactory;
import com.widyu.auth.repository.SocialUnlinkTaskRepository;
import com.widyu.member.Member;
import com.widyu.member.SocialAccount;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴 회원의 소셜 연동 해제. 탈퇴 트랜잭션에서 작업을 저장하고, 커밋 뒤 리스너와 재시도 스케줄러가 처리한다. → LLD-0059
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialUnlinkService {

    private static final Set<String> REFRESH_TOKEN_PROVIDERS = Set.of("apple", "naver");
    private static final int DUE_BATCH_SIZE = 100;

    private final SocialUnlinkTaskRepository repository;
    private final SocialLoginStrategyFactory strategyFactory;

    @Transactional
    public void schedule(Member member) {
        LocalDateTime now = LocalDateTime.now();
        for (SocialAccount account : member.getSocialAccounts()) {
            String provider = account.getProvider();
            if (!REFRESH_TOKEN_PROVIDERS.contains(provider)) {
                repository.save(SocialUnlinkTask.pending(member.getId(), provider, account.getOauthId(), null, now));
                continue;
            }
            String refreshToken = account.getRefreshToken();
            if (refreshToken == null || refreshToken.isBlank()) {
                log.warn("소셜 연동 해제 토큰 없음: memberId={}, provider={}", member.getId(), provider);
                continue;
            }
            repository.save(SocialUnlinkTask.pending(member.getId(), provider, account.getOauthId(), refreshToken, now));
        }
    }

    @Transactional(readOnly = true)
    public List<Long> findPendingIds(Long memberId) {
        return repository.findIdsByMemberIdAndStatus(memberId, SocialUnlinkTaskStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public List<Long> findDueIds() {
        return repository.findDueIds(SocialUnlinkTaskStatus.PENDING, LocalDateTime.now(),
                PageRequest.of(0, DUE_BATCH_SIZE));
    }

    // ponytail: 외부 HTTP 호출 동안 트랜잭션을 연다. 탈퇴는 드물어 감수하며, 늘어나면 LLD-0030처럼 선점·결과 기록 트랜잭션을 나눈다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(Long taskId) {
        SocialUnlinkTask task = repository.findById(taskId).filter(SocialUnlinkTask::isPending).orElse(null);
        if (task == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            strategyFactory.getStrategy(task.getProvider())
                    .withdrawSocialAccount(task.getRefreshToken(), task.getOauthId());
            task.complete(now);
            log.info("소셜 연동 해제 완료: taskId={}, memberId={}, provider={}",
                    taskId, task.getMemberId(), task.getProvider());
        } catch (Exception e) {
            task.fail(e.getClass().getSimpleName(), now);
            logFailure(task, e);
        }
    }

    private void logFailure(SocialUnlinkTask task, Exception e) {
        if (task.getStatus() == SocialUnlinkTaskStatus.FAILED) {
            log.error("소셜 연동 해제 최종 실패: taskId={}, memberId={}, provider={}, attempts={}, errorType={}",
                    task.getId(), task.getMemberId(), task.getProvider(), task.getAttemptCount(),
                    e.getClass().getSimpleName());
            return;
        }
        log.warn("소셜 연동 해제 실패, 재시도 예정: taskId={}, provider={}, attempts={}, errorType={}",
                task.getId(), task.getProvider(), task.getAttemptCount(), e.getClass().getSimpleName());
    }
}
