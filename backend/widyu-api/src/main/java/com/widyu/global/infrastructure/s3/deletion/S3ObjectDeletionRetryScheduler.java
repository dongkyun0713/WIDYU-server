package com.widyu.global.infrastructure.s3.deletion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class S3ObjectDeletionRetryScheduler {
    private final S3ObjectDeletionTaskService taskService;

    @Scheduled(cron = "0 */5 * * * *")
    public void retryPendingTasks() {
        taskService.findDueIds().forEach(this::process);
    }

    private void process(Long taskId) {
        try {
            taskService.process(taskId);
        } catch (Exception e) {
            log.error("S3 파일 삭제 재시도 실패: taskId={}, errorType={}",
                    taskId, e.getClass().getSimpleName());
        }
    }
}
