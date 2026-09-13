package com.widyu.goal.medicineschedule.event;

import com.widyu.global.infrastructure.s3.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class MedicationProofImageDeletionListener {

    private final S3Service s3Service;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deleteImagesAfterCommit(MedicationProofImagesDeletionEvent event) {
        int failedFileDeletionCount = deleteImages(event);
        if (failedFileDeletionCount > 0) {
            log.error("회원 복약 인증 사진 삭제 실패: memberId={}, failedFileDeletionCount={}",
                    event.memberId(), failedFileDeletionCount);
            return;
        }
        log.info("회원 복약 인증 사진 삭제 완료: memberId={}, imageCount={}",
                event.memberId(), event.imageUrls().size());
    }

    private int deleteImages(MedicationProofImagesDeletionEvent event) {
        int failedCount = 0;
        for (String imageUrl : event.imageUrls()) {
            if (!s3Service.deleteFile(imageUrl)) {
                failedCount++;
            }
        }
        return failedCount;
    }
}
