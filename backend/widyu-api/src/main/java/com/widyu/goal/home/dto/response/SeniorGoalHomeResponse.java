package com.widyu.goal.home.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.widyu.medicine.MedicationProof;
import com.widyu.medicine.MedicineSchedule;
import com.widyu.healthschedule.HealthSchedule;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public record SeniorGoalHomeResponse(
        @Schema(description = "약 스케줄 정보")
        MedicineInfo medicine,

        @Schema(description = "걸음 수 정보")
        StepsInfo steps,

        @Schema(description = "병원 일정 정보")
        HospitalInfo hospital
) {

    public static SeniorGoalHomeResponse of(
            MedicineInfo medicine,
            StepsInfo steps,
            HospitalInfo hospital
    ) {
        return new SeniorGoalHomeResponse(medicine, steps, hospital);
    }

    @Schema(description = "약 스케줄 정보")
    public record MedicineInfo(
            @Schema(description = "약 스케줄 ID", example = "1")
            Long medicineScheduleId,

            @Schema(description = "오늘 복용한 횟수", example = "2")
            Integer takenCount,

            @Schema(description = "오늘 총 복용 예정 횟수", example = "3")
            Integer totalCount,

            @Schema(description = "다음 복용 예정 개수", example = "4")
            Integer nextDoseCount,

            @Schema(description = "다음 알람 시간", example = "17:00")
            String nextAlarmTime,

            @Schema(description = "해당 스케줄의 복용 인증 이미지 (미복용 시 null)")
            String proofImageUrl
    ) {
        private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

        public static MedicineInfo from(
                MedicineSchedule nextSchedule,
                int takenCount,
                int totalCount,
                MedicationProof proof
        ) {
            return new MedicineInfo(
                    nextSchedule.getId(),
                    takenCount,
                    totalCount,
                    nextSchedule.getTotalCount(),
                    nextSchedule.getAlarmTime().format(TIME_FORMATTER),
                    firstProofImageUrl(proof)
            );
        }

        private static String firstProofImageUrl(MedicationProof proof) {
            if (proof == null || proof.getProofImageUrls().isEmpty()) {
                return null;
            }
            return proof.getProofImageUrls().getFirst();
        }
    }

    @Schema(description = "걸음 수 정보")
    public record StepsInfo(
            @Schema(description = "오늘 걸음 수", example = "9829")
            Integer steps,

            @Schema(description = "목표 걸음 수", example = "10000")
            Integer goal
    ) {
        public static StepsInfo of(Integer steps, Integer goal) {
            return new StepsInfo(steps, goal);
        }
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
