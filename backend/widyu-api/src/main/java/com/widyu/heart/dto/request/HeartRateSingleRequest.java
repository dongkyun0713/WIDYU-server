package com.widyu.heart.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDateTime;

/** 측정값 1건을 즉시 전송하는 요청(ADR-0017). */
public record HeartRateSingleRequest(
        // AI가 0과 300을 400으로 거부하므로 같은 범위로 맞춘다.
        @NotNull(message = "심박수는 필수입니다.")
        @Min(value = 1, message = "심박수는 1 이상이어야 합니다.")
        @Max(value = 299, message = "심박수는 299 이하여야 합니다.")
        Integer heartRate,

        @NotNull(message = "측정 시각은 필수입니다.")
        LocalDateTime measuredAt,

        String location, // 현재 위치 주소 (이상치 감지 시 위급상황 기록에 사용)

        @Pattern(
                regexp = "^(REST|LOW|ACTIVE|UNKNOWN|\\s*)$",
                message = "활동 상태는 REST, LOW, ACTIVE, UNKNOWN 중 하나여야 합니다."
        )
        String context
) {

    private static final String FALLBACK_CONTEXT = "UNKNOWN";

    public static HeartRateSingleRequest of(
            Integer heartRate, LocalDateTime measuredAt, String location, String context) {
        return new HeartRateSingleRequest(heartRate, measuredAt, location, context);
    }

    public HeartRateMeasurement toMeasurement() {
        return new HeartRateMeasurement(heartRate, measuredAt);
    }

    /**
     * AI에 전달할 활동 상태. 현재는 항상 {@code UNKNOWN}이다.
     * AI가 {@code context=REST}(L1 경로)에서 위급을 판정하지 못해 고정 임계값 경로로 고정한다(#477).
     */
    public String normalizedContext() {
        // TODO(#477): AI가 L1 경로에서도 위급을 판정하게 되면 원본 context를 그대로 전달한다.
        return FALLBACK_CONTEXT;
    }
}
