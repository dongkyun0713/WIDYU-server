package com.widyu.global.infrastructure.s3.deletion;

import com.widyu.global.storage.S3ObjectDeletionTask;
import com.widyu.global.storage.S3ObjectDeletionTaskStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class S3ObjectDeletionTaskTransactionService {

    private final S3ObjectDeletionTaskRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<DeletionCommand> claimForProcessing(Long id) {
        LocalDateTime now = LocalDateTime.now();
        int claimed = repository.claimForProcessing(id, now, now.plusMinutes(10));
        if (claimed == 0) {
            return Optional.empty();
        }

        return repository.findById(id)
                .map(task -> new DeletionCommand(task.getId(), task.getObjectKey(), task.getProcessingAttempt()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordResult(Long id, int attempt, boolean deleted, String errorType) {
        repository.findByIdForUpdate(id).filter(task -> task.isProcessing(attempt)).ifPresent(task -> {
            if (deleted) {
                task.complete();
                return;
            }
            task.fail(errorType);
        });
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Long> findDueIds() {
        return repository.findDueIds(
                S3ObjectDeletionTaskStatus.PENDING,
                S3ObjectDeletionTaskStatus.PROCESSING,
                LocalDateTime.now(),
                PageRequest.of(0, 100));
    }

    public record DeletionCommand(Long id, String objectKey, int attempt) {
    }
}
