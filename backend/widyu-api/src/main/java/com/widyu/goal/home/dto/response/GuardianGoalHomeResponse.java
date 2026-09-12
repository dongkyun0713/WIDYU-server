package com.widyu.goal.home.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.widyu.goal.medicineschedule.dto.response.MedicationStatus;
import com.widyu.healthschedule.HealthSchedule;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

public record GuardianGoalHomeResponse(
        @Schema(description = "약 스케줄 정보")
        MedicineInfo medicine,

        @Schema(description = "걸음 수 정보")
        StepsInfo steps,

        @Schema(description = "병원 일정 정보")
        HospitalInfo hospital
) {
    public static GuardianGoalHomeResponse of(MedicineInfo medicine, StepsInfo steps, HospitalInfo hospital) {
        return new GuardianGoalHomeResponse(medicine, steps, hospital);
    }

    @Schema(description = "약 스케줄 정보")
    public record MedicineInfo(
            @Schema(description = "오늘 총 복용 예정 개수", example = "6")
            Integer totalCount,

            @Schema(description = "오늘 복용한 개수", example = "1")
            Integer takenCount,

            @Schema(description = "약 스케줄 목록")
            List<ScheduleItem> medicineSchedules
    ) {
    }

    @Schema(description = "약 스케줄 항목")
    public record ScheduleItem(
            @Schema(description = "약 스케줄 ID", example = "101")
            Long medicineScheduleId,

            @Schema(description = "알람 시간", example = "19:00")
            String alarmTime,

            @Schema(description = "복용 상태 (DONE: 복용 완료, UPCOMING: 복용 시간 전, MISSED: 놓침)", example = "DONE")
            MedicationStatus status,

            @Schema(description = "복용 인증 이미지 URL", example = "https://www.widyu.shop/img")
            String proofImageUrl,

            @Schema(description = "약 목록")
            List<MedicineItem> medicines
    ) {
    }

    @Schema(description = "약 정보")
    public record MedicineItem(
            @Schema(description = "약 이름", example = "위염약")
            String name,

            @Schema(description = "복용 개수", example = "5")
            Integer count
    ) {
    }

    @Schema(description = "걸음 수 정보")
    public record StepsInfo(
            @Schema(description = "오늘 걸음 수", example = "9829")
            Integer steps,

            @Schema(description = "목표 걸음 수", example = "10000")
            Integer goal
    ) {
    }

    @Schema(description = "병원 일정 정보")
    public record HospitalInfo(
            @Schema(description = "병원 일정 ID", example = "1")
            Long hospitalScheduleId,

            @Schema(description = "D-day", example = "14")
            Integer dday,

            @Schema(description = "병원 방문 일시", example = "2025-08-26T17:00:00")
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
            LocalDateTime datetime,

            @Schema(description = "병원 이름", example = "고려대학교 의과대학 부속병원")
            String name,

            @Schema(description = "병원 주소", example = "병원 주소")
            String address,

            @Schema(description = "위도", example = "37.5894")
            Double latitude,

            @Schema(description = "경도", example = "127.0327")
            Double longitude
    ) {
        public static HospitalInfo from(HealthSchedule schedule, int dday) {
            return new HospitalInfo(
                    schedule.getId(),
                    dday,
                    schedule.getScheduledAt(),
                    schedule.getScheduleName(),
                    schedule.getPlaceAddress(),
                    schedule.getLatitude(),
                    schedule.getLongitude()
            );
        }
    }
}
