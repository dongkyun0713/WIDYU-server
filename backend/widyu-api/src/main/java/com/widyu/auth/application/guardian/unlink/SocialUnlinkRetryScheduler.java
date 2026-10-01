package com.widyu.auth.application.guardian.unlink;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SocialUnlinkRetryScheduler {

    private final SocialUnlinkService socialUnlinkService;

    // ponytail: single-node scheduler, add ShedLock before running multiple API instances.
    @Scheduled(fixedDelay = 600_000)
    public void retryDueTasks() {
        socialUnlinkService.findDueIds().forEach(this::process);
    }

    private void process(Long taskId) {
        try {
            socialUnlinkService.process(taskId);
        } catch (Exception e) {
            log.error("소셜 연동 해제 재시도 실패: taskId={}, errorType={}", taskId, e.getClass().getSimpleName());
        }
    }
}
