package com.widyu.global.infrastructure.s3.deletion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class S3ObjectDeletionListener {

    private final S3ObjectDeletionTaskService taskService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deleteImagesAfterCommit(S3ObjectDeletionEvent event) {
        event.taskIds().forEach(this::process);
    }

    private void process(Long taskId) {
        try {
            taskService.process(taskId);
        } catch (Exception e) {
            log.error("S3 파일 삭제 작업 처리 실패: taskId={}, errorType={}",
                    taskId, e.getClass().getSimpleName());
        }
    }
}
