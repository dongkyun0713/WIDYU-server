package com.widyu.goal.medicineschedule.event;

import com.widyu.goal.medicineschedule.application.MedicationProofImageDeletionTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class MedicationProofImageDeletionListener {

    private final MedicationProofImageDeletionTaskService taskService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deleteImagesAfterCommit(MedicationProofImagesDeletionEvent event) {
        event.taskIds().forEach(this::process);
    }

    private void process(Long taskId) {
        try {
            taskService.process(taskId);
        } catch (Exception e) {
            log.error("복약 인증 사진 삭제 작업 처리 실패: taskId={}, errorType={}",
                    taskId, e.getClass().getSimpleName());
        }
    }
}
