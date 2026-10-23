package com.widyu.goal.healthschedule.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.widyu.healthschedule.HealthSchedule;
import com.widyu.healthschedule.ProgressStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Schema(description = "오늘 건강 일정 항목 (예정 시각이 지난 일정과 완료된 일정도 포함)")
public record TodayHealthScheduleItem(
        @Schema(description = "건강 일정 ID", example = "12")
        Long healthScheduleId,

        @Schema(description = "일정 이름", example = "내과 정기검진")
        String scheduleName,

        @Schema(description = "일정 일시", example = "2026-10-23T09:30:00")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime scheduledAt,

        @Schema(description = "장소 주소", example = "서울특별시 성북구 고려대로 73")
        String placeAddress,

        @Schema(description = "위도", example = "37.5894")
        Double latitude,

        @Schema(description = "경도", example = "127.0327")
        Double longitude,

        @Schema(description = "진행 상태 (UPCOMING: 방문 전, INCOMPLETE: 미방문, COMPLETED: 방문 완료). "
                + "일정 상세 조회(/daily)와 같은 표시 상태", example = "INCOMPLETE")
        ProgressStatus progressStatus,

        @Schema(description = "예정 시각 경과 여부 (scheduledAt이 응답 생성 시각 이하이면 true). progressStatus와 별개로 판정",
                example = "true")
        boolean elapsed
) {

    public static TodayHealthScheduleItem from(HealthSchedule schedule, LocalDateTime now) {
        return new TodayHealthScheduleItem(
                schedule.getId(),
                schedule.getScheduleName(),
                schedule.getScheduledAt(),
                schedule.getPlaceAddress(),
                schedule.getLatitude(),
                schedule.getLongitude(),
                schedule.getDisplayProgressStatus(now),
                !schedule.getScheduledAt().isAfter(now)
        );
    }

    /** now와 같은 날짜의 일정만 scheduledAt 오름차순으로 담는다. 더 넓은 범위를 조회한 결과를 넘겨도 된다. */
    public static List<TodayHealthScheduleItem> listFrom(List<HealthSchedule> schedules, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        return schedules.stream()
                .filter(schedule -> schedule.getScheduledAt().toLocalDate().isEqual(today))
                .sorted(Comparator.comparing(HealthSchedule::getScheduledAt))
                .map(schedule -> from(schedule, now))
                .toList();
    }
}
