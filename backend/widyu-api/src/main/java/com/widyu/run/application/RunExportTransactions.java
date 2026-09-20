package com.widyu.run.application;

import com.widyu.run.RunExport;
import com.widyu.run.RunExportStatus;
import com.widyu.run.repository.RunExportRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내보내기 잡의 상태 전이만 담는 빈(LLD-0050 5.2).
 * 워커가 자기 메서드를 부르면 프록시를 타지 않아 {@code REQUIRES_NEW}가 걸리지 않는다.
 * 그래서 {@code FcmOutboxTransactions}와 같이 별도 빈으로 뺀다.
 */
@Component
@RequiredArgsConstructor
public class RunExportTransactions {

    private final RunExportRepository runExportRepository;

    /** {@code QUEUED} 한 건을 선점한다. 조건부 UPDATE라 워커가 여럿이어도 하나만 집는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<RunExport> claim() {
        Optional<RunExport> queued = runExportRepository
                .findFirstByStatusOrderByRequestedAtMsAscIdAsc(RunExportStatus.QUEUED);
        if (queued.isEmpty()) {
            return Optional.empty();
        }
        if (runExportRepository.claim(queued.get().getId(), System.currentTimeMillis()) == 0) {
            return Optional.empty();
        }
        return runExportRepository.findById(queued.get().getId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Long id, String s3Key, long bytes, String sha256) {
        runExportRepository.findById(id)
                .ifPresent(export -> export.finish(s3Key, bytes, sha256, System.currentTimeMillis()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long id, String errorType) {
        runExportRepository.findById(id)
                .ifPresent(export -> export.fail(errorType, System.currentTimeMillis()));
    }
}
