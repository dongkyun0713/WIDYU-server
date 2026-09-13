package com.widyu.goal.medicineschedule.application;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.global.infrastructure.s3.S3DeleteResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MedicationProofImageDeletionTaskService {
    private final MedicationProofImageDeletionTaskTransactionService transactionService;
    private final S3Service s3Service;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void process(Long id) {
        transactionService.claimForProcessing(id).ifPresent(this::process);
    }

    public List<Long> findDueIds() {
        return transactionService.findDueIds();
    }

    private void process(MedicationProofImageDeletionTaskTransactionService.DeletionCommand command) {
        S3DeleteResult result;
        try {
            result = s3Service.deleteFileByKey(command.objectKey());
        } catch (Exception e) {
            transactionService.recordResult(command.id(), command.attempt(), false, e.getClass().getSimpleName());
            return;
        }
        transactionService.recordResult(command.id(), command.attempt(), result.deleted(), result.errorType());
    }
}
