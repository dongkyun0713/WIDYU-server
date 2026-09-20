package com.widyu.run.application;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.infrastructure.s3.S3DirectUploadService;
import com.widyu.global.properties.SensorProperties;
import com.widyu.run.CollectionRun;
import com.widyu.run.RunExport;
import com.widyu.run.RunExportStatus;
import com.widyu.run.dto.response.RunExportResponse;
import com.widyu.run.repository.CollectionRunRepository;
import com.widyu.run.repository.RunExportRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내보내기 요청과 상태 조회(LLD-0050 5.1·5.8).
 * 실제 조립은 {@link RunExportWorker}가 큐에서 집어 돌린다(ADR-0032 결정 2).
 */
@Service
@RequiredArgsConstructor
public class RunExportService {

    private static final String EXPORT_ID_PREFIX = "exp-";
    private static final List<RunExportStatus> IN_PROGRESS =
            List.of(RunExportStatus.QUEUED, RunExportStatus.RUNNING);

    private final RunExportRepository runExportRepository;
    private final CollectionRunRepository collectionRunRepository;
    private final S3DirectUploadService s3DirectUploadService;
    private final SensorProperties sensorProperties;

    @Transactional
    public RunExportResponse request(String runId) {
        CollectionRun run = collectionRunRepository.findByRunId(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        if (run.isOpen()) {
            throw new BusinessException(ErrorCode.RUN_NOT_CLOSED);
        }

        // 같은 회차를 두 번 조립하지 않는다. 진행 중인 잡을 그대로 돌려준다.
        List<RunExport> inProgress =
                runExportRepository.findByRunIdAndStatusInOrderByIdAsc(runId, IN_PROGRESS);
        if (!inProgress.isEmpty()) {
            return RunExportResponse.from(inProgress.get(0));
        }

        RunExport export = runExportRepository.save(RunExport.builder()
                .exportId(EXPORT_ID_PREFIX + UUID.randomUUID().toString().replace("-", ""))
                .runId(runId)
                .status(RunExportStatus.QUEUED)
                .requestedAtMs(System.currentTimeMillis())
                .serverBuild(sensorProperties.export().serverBuild())
                .build());
        return RunExportResponse.from(export);
    }

    @Transactional(readOnly = true)
    public RunExportResponse status(String runId, String exportId) {
        RunExport export = runExportRepository.findByExportIdAndRunId(exportId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_EXPORT_NOT_FOUND));
        if (!export.isDone()) {
            return RunExportResponse.from(export);
        }
        // 다운로드 URL은 조회 시점에 발급한다. 저장해 두면 만료된 URL을 돌려주게 된다.
        Duration expiry = Duration.ofMinutes(sensorProperties.export().presignMinutes());
        String downloadUrl = s3DirectUploadService.presignGet(export.getS3Key(), expiry);
        return RunExportResponse.from(
                export, downloadUrl, System.currentTimeMillis() + expiry.toMillis());
    }
}
