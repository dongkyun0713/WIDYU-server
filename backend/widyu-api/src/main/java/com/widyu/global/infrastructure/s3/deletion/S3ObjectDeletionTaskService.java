package com.widyu.global.infrastructure.s3.deletion;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.global.infrastructure.s3.S3DeleteResult;
import com.widyu.global.storage.S3ObjectDeletionTask;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3ObjectDeletionTaskService {
    private final S3ObjectDeletionTaskTransactionService transactionService;
    private final S3ObjectDeletionTaskRepository repository;
    private final S3Service s3Service;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 호출자 트랜잭션 안에 삭제 작업을 저장한다. 커밋 뒤 리스너가 삭제하고, 실패하면 스케줄러가 재시도한다.
     * 롤백되면 작업도 함께 사라진다. 비어 있거나 버킷 밖의 주소는 건너뛴다.
     */
    @Transactional
    public void schedule(Long memberId, List<String> fileUrls) {
        List<S3ObjectDeletionTask> tasks = fileUrls.stream()
                .filter(url -> url != null && !url.isBlank())
                .map(url -> createTask(memberId, url))
                .filter(Objects::nonNull)
                .toList();
        if (tasks.isEmpty()) {
            return;
        }
        List<Long> taskIds = repository.saveAll(tasks).stream().map(S3ObjectDeletionTask::getId).toList();
        eventPublisher.publishEvent(new S3ObjectDeletionEvent(taskIds));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void process(Long id) {
        transactionService.claimForProcessing(id).ifPresent(this::process);
    }

    public List<Long> findDueIds() {
        return transactionService.findDueIds();
    }

    private void process(S3ObjectDeletionTaskTransactionService.DeletionCommand command) {
        S3DeleteResult result;
        try {
            result = s3Service.deleteFileByKey(command.objectKey());
        } catch (Exception e) {
            transactionService.recordResult(command.id(), command.attempt(), false, e.getClass().getSimpleName());
            return;
        }
        transactionService.recordResult(command.id(), command.attempt(), result.deleted(), result.errorType());
    }

    private S3ObjectDeletionTask createTask(Long memberId, String fileUrl) {
        try {
            return S3ObjectDeletionTask.pending(memberId, s3Service.extractObjectKey(fileUrl));
        } catch (Exception e) {
            log.error("S3 파일 삭제 작업 생성 실패: memberId={}, errorType={}", memberId, e.getClass().getSimpleName());
            return null;
        }
    }
}
