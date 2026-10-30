package com.widyu.goal.home.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.widyu.goal.healthschedule.dto.response.TodayHealthScheduleItem;
import com.widyu.medicine.MedicationProof;
import com.widyu.medicine.MedicineSchedule;
import com.widyu.healthschedule.HealthSchedule;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public record SeniorGoalHomeResponse(
        @Schema(description = "약 스케줄 정보")
        MedicineInfo medicine,

        @Schema(description = "걸음 수 정보")
        StepsInfo steps,

        @Schema(description = "다음 병원 일정 (현재 시각 이후 1개월 이내 가장 가까운 일정). null이면 일정 추가 요청 컴포넌트를 표시합니다.")
        HospitalInfo hospital,

        @Schema(description = "오늘 건강 일정 전체 (예정 시각이 지난 일정과 완료된 일정 포함, 일시 오름차순). 없으면 빈 배열")
        List<TodayHealthScheduleItem> todaySchedules
) {

    public static SeniorGoalHomeResponse of(
            MedicineInfo medicine,
            StepsInfo steps,
            HospitalInfo hospital,
            List<TodayHealthScheduleItem> todaySchedules
    ) {
        return new SeniorGoalHomeResponse(medicine, steps, hospital, todaySchedules);
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
            String proofImageUrl,

            @Schema(description = "오늘 유효한 일정별 복용 상태 (알람 시각 오름차순)")
            List<ScheduleStatus> scheduleStatuses
    ) {
        public static MedicineInfo from(
                MedicineSchedule nextSchedule,
                int takenCount,
                int totalCount,
                MedicationProof proof,
                List<ScheduleStatus> scheduleStatuses
        ) {
            return new MedicineInfo(
                    nextSchedule.getId(),
                    takenCount,
                    totalCount,
                    nextSchedule.getTotalCount(),
                    nextSchedule.getAlarmTime().format(TIME_FORMATTER),
                    firstProofImageUrl(proof),
                    scheduleStatuses
            );
        }
    }

    @Schema(description = "일정별 복용 상태")
    public record ScheduleStatus(
            @Schema(description = "약 스케줄 ID (오늘 유효한 버전, 복용 인증에 그대로 사용)", example = "1")
            Long medicineScheduleId,

            @Schema(description = "복용 시각", example = "10:40")
            String doseTime,

            @Schema(description = "이 시각에 복용할 개수", example = "1")
            Integer doseCount,

            @Schema(description = "오늘 복용 인증 여부", example = "true")
            boolean taken,

            @Schema(description = "복용 인증 이미지 (미복용 시 null)")
            String proofImageUrl
    ) {
        /** {@code proof}는 오늘 이 일정의 대표 인증이며, 없으면 null이다. */
        public static ScheduleStatus from(MedicineSchedule schedule, MedicationProof proof) {
            return new ScheduleStatus(
                    schedule.getId(),
                    schedule.getAlarmTime().format(TIME_FORMATTER),
                    schedule.getTotalCount(),
                    proof != null,
                    firstProofImageUrl(proof)
            );
        }
    }

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private static String firstProofImageUrl(MedicationProof proof) {
        if (proof == null || proof.getProofImageUrls().isEmpty()) {
            return null;
        }
        return proof.getProofImageUrls().getFirst();
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
