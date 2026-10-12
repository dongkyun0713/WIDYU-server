package com.widyu.goal.healthschedule.dto.request;

import com.widyu.healthschedule.ProgressStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public record HealthScheduleUpdateRequest(
        @Size(max = 255, message = "일정 이름은 255자 이하로 입력해 주세요.")
        String scheduleName,

        @Size(max = 255, message = "장소 주소는 255자 이하로 입력해 주세요.")
        String placeAddress,

        @DecimalMin(value = "-90", message = "위도는 -90 이상 90 이하여야 합니다.")
        @DecimalMax(value = "90", message = "위도는 -90 이상 90 이하여야 합니다.")
        Double latitude,

        @DecimalMin(value = "-180", message = "경도는 -180 이상 180 이하여야 합니다.")
        @DecimalMax(value = "180", message = "경도는 -180 이상 180 이하여야 합니다.")
        Double longitude,

        LocalDateTime scheduledAt,
        ProgressStatus progressStatus
) {
}
