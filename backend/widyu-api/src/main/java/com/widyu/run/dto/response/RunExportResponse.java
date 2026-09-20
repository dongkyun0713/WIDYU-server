package com.widyu.run.dto.response;

import com.widyu.run.RunExport;
import com.widyu.run.RunExportStatus;

public record RunExportResponse(
        String exportId,
        String runId,
        RunExportStatus status,
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

    public static RunExportResponse from(
            RunExport export, String downloadUrl, Long downloadExpiresAtMs) {
        return new RunExportResponse(
                export.getExportId(),
                export.getRunId(),
                export.getStatus(),
                export.getRequestedAtMs(),
                export.getStartedAtMs(),
                export.getFinishedAtMs(),
                "run_%s.zip".formatted(export.getRunId()),
                export.getBytes(),
                export.getSha256(),
                downloadUrl,
                downloadExpiresAtMs,
                export.getErrorType());
    }

    public static RunExportResponse from(RunExport export) {
        return from(export, null, null);
    }
}
