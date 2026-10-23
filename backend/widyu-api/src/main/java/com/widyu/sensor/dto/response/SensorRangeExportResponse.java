package com.widyu.sensor.dto.response;

import com.widyu.sensor.SensorRangeExport;
import com.widyu.sensor.SensorRangeExportStatus;

public record SensorRangeExportResponse(
        String exportId,
        Long memberId,
        Long startAtMs,
        Long endAtMs,
        SensorRangeExportStatus status,
        Long requestedAtMs,
        Long startedAtMs,
        Long finishedAtMs,
        String fileName,
        Long bytes,
        String sha256,
        String downloadUrl,
        Long downloadExpiresAtMs,
        String errorType
) {
    public static SensorRangeExportResponse from(SensorRangeExport export) {
        return from(export, null, null);
    }

    public static SensorRangeExportResponse from(
            SensorRangeExport export, String downloadUrl, Long downloadExpiresAtMs) {
        return new SensorRangeExportResponse(
                export.getExportId(), export.getMemberId(), export.getStartAtMs(), export.getEndAtMs(),
                export.getStatus(), export.getRequestedAtMs(), export.getStartedAtMs(), export.getFinishedAtMs(),
                "sensor_%s.zip".formatted(export.getExportId()), export.getBytes(), export.getSha256(),
                downloadUrl, downloadExpiresAtMs, export.getErrorType());
    }
}
