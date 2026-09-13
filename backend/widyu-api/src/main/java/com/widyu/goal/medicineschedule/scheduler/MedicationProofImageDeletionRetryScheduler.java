package com.widyu.goal.medicineschedule.scheduler;

import com.widyu.goal.medicineschedule.application.MedicationProofImageDeletionTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MedicationProofImageDeletionRetryScheduler {
    private final MedicationProofImageDeletionTaskService taskService;

    @Scheduled(cron = "0 */5 * * * *")
    public void retryPendingTasks() {
        taskService.findDueIds().forEach(this::process);
    }

    private void process(Long taskId) {
        try {
            taskService.process(taskId);
        } catch (Exception e) {
            log.error("복약 인증 사진 삭제 재시도 실패: taskId={}, errorType={}",
                    taskId, e.getClass().getSimpleName());
        }
    }
}
