package com.widyu.sensor.application;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.infrastructure.s3.S3DirectUploadService;
import com.widyu.global.properties.SensorProperties;
import com.widyu.member.repository.MemberRepository;
import com.widyu.sensor.SensorRangeExport;
import com.widyu.sensor.SensorRangeExportStatus;
import com.widyu.sensor.dto.request.SensorRangeExportRequest;
import com.widyu.sensor.dto.response.SensorRangeExportResponse;
import com.widyu.sensor.repository.SensorRangeExportRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SensorRangeExportService {

    private static final long MAX_RANGE_MS = Duration.ofDays(1).toMillis();
    private static final List<SensorRangeExportStatus> IN_PROGRESS =
            List.of(SensorRangeExportStatus.QUEUED, SensorRangeExportStatus.RUNNING);

    private final MemberRepository memberRepository;
    private final SensorRangeExportRepository exportRepository;
    private final S3DirectUploadService s3DirectUploadService;
    private final SensorProperties sensorProperties;

    @Transactional
    public SensorRangeExportResponse request(Long memberId, SensorRangeExportRequest request) {
        validateRange(request);
        memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        List<SensorRangeExport> inProgress = exportRepository
                .findByMemberIdAndStartAtMsAndEndAtMsAndStatusInOrderByIdAsc(
                        memberId, request.startAtMs(), request.endAtMs(), IN_PROGRESS);
        if (!inProgress.isEmpty()) {
            return SensorRangeExportResponse.from(inProgress.get(0));
        }

        SensorRangeExport export = exportRepository.save(SensorRangeExport.builder()
                .exportId("sexp-" + UUID.randomUUID().toString().replace("-", ""))
                .memberId(memberId)
                .startAtMs(request.startAtMs())
                .endAtMs(request.endAtMs())
                .status(SensorRangeExportStatus.QUEUED)
                .requestedAtMs(System.currentTimeMillis())
                .build());
        return SensorRangeExportResponse.from(export);
    }

    @Transactional(readOnly = true)
    public SensorRangeExportResponse status(Long memberId, String exportId) {
        SensorRangeExport export = exportRepository.findByExportIdAndMemberId(exportId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SENSOR_EXPORT_NOT_FOUND));
        if (!export.isDone()) {
            return SensorRangeExportResponse.from(export);
        }
        Duration expiry = Duration.ofMinutes(sensorProperties.export().presignMinutes());
        String downloadUrl = s3DirectUploadService.presignGet(export.getS3Key(), expiry);
        return SensorRangeExportResponse.from(
                export, downloadUrl, System.currentTimeMillis() + expiry.toMillis());
    }

    private void validateRange(SensorRangeExportRequest request) {
        if (request == null || request.startAtMs() == null || request.endAtMs() == null) {
            throw new BusinessException(ErrorCode.SENSOR_EXPORT_RANGE_INVALID);
        }
        long start = request.startAtMs();
        long end = request.endAtMs();
        if (start < 0 || start >= end || end > System.currentTimeMillis() || end - start > MAX_RANGE_MS) {
            throw new BusinessException(ErrorCode.SENSOR_EXPORT_RANGE_INVALID);
        }
    }
}
