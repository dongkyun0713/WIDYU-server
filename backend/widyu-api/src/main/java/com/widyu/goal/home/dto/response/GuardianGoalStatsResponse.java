package com.widyu.goal.home.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record GuardianGoalStatsResponse(
        @Schema(description = "지난주 목표 달성률", example = "0.80")
        Double lastWeekGoalRate,

        @Schema(description = "오늘 목표 달성률", example = "0.75")
        Double todayGoalRate,

        @Schema(description = "이번주 일별 목표 달성률 (일~토)", example = "[0.6, 0.24, 0.53, 0.75, 0.85, 0.90, 1.0]")
        List<Double> thisWeekGoalRates
) {

    public static GuardianGoalStatsResponse of(
            Double lastWeekGoalRate,
            Double todayGoalRate,
            List<Double> thisWeekGoalRates
    ) {
        return new GuardianGoalStatsResponse(lastWeekGoalRate, todayGoalRate, thisWeekGoalRates);
    }
}
