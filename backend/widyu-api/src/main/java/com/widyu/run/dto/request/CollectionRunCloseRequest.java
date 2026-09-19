package com.widyu.run.dto.request;

import jakarta.validation.constraints.Size;

public record CollectionRunCloseRequest(
        Long endedAtMs,

        @Size(max = 500, message = "품질 메모는 500자 이하여야 합니다.")
        String qualityNotes,

        @Size(max = 64, message = "결측 사유는 64자 이하여야 합니다.")
        String missingReason
) {

    public static CollectionRunCloseRequest of(
            Long endedAtMs, String qualityNotes, String missingReason) {
        return new CollectionRunCloseRequest(endedAtMs, qualityNotes, missingReason);
    }
}
