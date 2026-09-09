package com.widyu.heart.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record HeartRateMeasurement(
        // AI가 0과 300을 400으로 거부하므로 같은 범위로 맞춘다.
        @NotNull(message = "심박수는 필수입니다.")
        @Min(value = 1, message = "심박수는 1 이상이어야 합니다.")
        @Max(value = 299, message = "심박수는 299 이하여야 합니다.")
        Integer heartRate,

        @NotNull(message = "측정 시각은 필수입니다.")
        LocalDateTime measuredAt
) {
}
