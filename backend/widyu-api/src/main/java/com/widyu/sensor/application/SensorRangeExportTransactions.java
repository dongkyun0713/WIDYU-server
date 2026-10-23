package com.widyu.sensor.application;

import com.widyu.sensor.SensorRangeExport;
import com.widyu.sensor.SensorRangeExportStatus;
import com.widyu.sensor.repository.SensorRangeExportRepository;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 워커와 분리된 짧은 상태 변경 트랜잭션. */
@Component
@RequiredArgsConstructor
public class SensorRangeExportTransactions {

    private static final long RUNNING_TIMEOUT_MS = Duration.ofHours(12).toMillis();

    private final SensorRangeExportRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<SensorRangeExport> claim() {
        long now = System.currentTimeMillis();
        repository.requeueExpiredRunning(now - RUNNING_TIMEOUT_MS);
        Optional<SensorRangeExport> queued = repository
                .findFirstByStatusOrderByRequestedAtMsAscIdAsc(SensorRangeExportStatus.QUEUED);
        if (queued.isEmpty()) {
            return Optional.empty();
        }
        if (repository.claim(queued.get().getId(), now) == 0) {
            return Optional.empty();
        }
        return repository.findById(queued.get().getId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finish(Long id, long startedAtMs, String s3Key, long bytes, String sha256) {
        return repository.finishIfRunning(
                id, startedAtMs, s3Key, bytes, sha256, System.currentTimeMillis()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long id, long startedAtMs, String errorType) {
        repository.failIfRunning(id, startedAtMs, errorType, System.currentTimeMillis());
    }
}
