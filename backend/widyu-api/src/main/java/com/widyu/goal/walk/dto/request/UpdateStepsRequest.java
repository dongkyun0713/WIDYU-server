package com.widyu.goal.walk.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record UpdateStepsRequest(
        @NotNull(message = "걸음 수는 필수입니다.")
        @Min(value = 0, message = "걸음 수는 0 이상이어야 합니다.")
        Integer steps,

        @NotNull(message = "연동 날짜는 필수입니다.")
        LocalDate date
) {
}
