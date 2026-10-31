package com.widyu.home.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.widyu.album.Album;
import com.widyu.goal.healthschedule.dto.response.TodayHealthScheduleItem;
import com.widyu.healthschedule.HealthSchedule;
import com.widyu.heart.HeartRateResult;
import com.widyu.heart.HeartRateStatus;
import com.widyu.medicine.MedicationProof;
import com.widyu.medicine.MedicineSchedule;
import com.widyu.walk.Walk;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

public record SeniorHomeCardsResponse(

        @Schema(description = "심박수 · 착용 상태")
        HeartRateInfo heartRate,

        @Schema(description = "약 복용 카드")
        MedicineInfo medicine,

        @Schema(description = "추억 앨범 카드 (최대 3장, 점수 기반 추천)")
        List<AlbumInfo> albums,

        @Schema(description = "건강달력 카드의 다음 일정 (현재 시각 이후 1개월 이내 가장 가까운 일정). null이면 일정 추가 요청 컴포넌트를 표시합니다.")
        HealthScheduleInfo healthSchedule,

        @Schema(description = "건강달력 카드의 오늘 일정 전체 (예정 시각이 지난 일정과 완료된 일정 포함, 일시 오름차순). 없으면 빈 배열")
        List<TodayHealthScheduleItem> todaySchedules,

        @Schema(description = "걷기 카드")
        WalkInfo walk
) {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final long WEARING_THRESHOLD_SECONDS = 30;

    public static SeniorHomeCardsResponse of(HeartRateInfo heartRate, MedicineInfo medicine,
                                             List<AlbumInfo> albums, HealthScheduleInfo healthSchedule,
                                             List<TodayHealthScheduleItem> todaySchedules, WalkInfo walk) {
        return new SeniorHomeCardsResponse(heartRate, medicine, albums, healthSchedule, todaySchedules, walk);
    }

    public record HeartRateInfo(
            @Schema(description = "워치 착용 여부 (최근 30초 이내 심박 데이터 수신 시 true)", example = "true")
            boolean isWearing,

            @Schema(description = "심박수 상태", example = "NORMAL")
            HeartRateStatus heartRateStatus,

            @Schema(description = "심박수 BPM, 미착용 시 null", example = "65")
            Integer bpm
    ) {
        public static HeartRateInfo from(HeartRateResult result) {
            boolean wearing = isRecentlyMeasured(result);
            if (!wearing) {
                return unknown();
            }
            return new HeartRateInfo(wearing, result.getStatus(), result.getHeartRate());
        }

        public static HeartRateInfo unknown() {
            return new HeartRateInfo(false, HeartRateStatus.UNKNOWN, null);
        }

        private static boolean isRecentlyMeasured(HeartRateResult result) {
            if (result.getStatus() == HeartRateStatus.UNKNOWN || result.getMeasuredAt() == null) {
                return false;
            }
            long seconds = Duration.between(result.getMeasuredAt(), LocalDateTime.now()).getSeconds();
            return seconds >= 0 && seconds <= WEARING_THRESHOLD_SECONDS;
        }
    }

    public record MedicineInfo(
            @Schema(description = "다음 복용 스케줄 ID", example = "1")
            Long medicineScheduleId,

            @Schema(description = "오늘 복용 완료 횟수", example = "1")
            Integer todayTakenCount,

            @Schema(description = "오늘 총 복용 예정 횟수 (동그라미 개수)", example = "6")
            Integer todayTotalCount,

            @Schema(description = "다음 복용 예정 정제 수", example = "4")
            Integer nextDoseCount,

            @Schema(description = "다음 알람 시간 (HH:mm)", example = "14:00")
            String nextAlarmTime,

            @Schema(description = "스케줄별 복용 상태 (동그라미 색상용, 알람 시간 오름차순)")
            List<ScheduleStatus> scheduleStatuses,

            @Schema(description = "해당 스케줄의 복용 인증 이미지 (미복용 시 null)")
            String proofImageUrl
    ) {
        public static MedicineInfo from(
                MedicineSchedule nextSchedule,
                int todayTakenCount,
                int todayTotalCount,
                List<ScheduleStatus> scheduleStatuses,
                MedicationProof proof
        ) {
            return new MedicineInfo(
                    nextSchedule.getId(),
                    todayTakenCount,
                    todayTotalCount,
                    nextSchedule.getTotalCount(),
                    nextSchedule.getAlarmTime().format(TIME_FORMATTER),
                    scheduleStatuses,
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

    public record ScheduleStatus(
            @Schema(description = "복용 시간 (HH:mm)", example = "08:00")
            String doseTime,

            @Schema(description = "복용 여부 (true=주황, false=회색)", example = "true")
            boolean taken
    ) {
        public static ScheduleStatus from(MedicineSchedule schedule, boolean taken) {
            return new ScheduleStatus(schedule.getAlarmTime().format(TIME_FORMATTER), taken);
        }
    }

    public record AlbumInfo(
            @Schema(description = "앨범 ID", example = "10")
            Long albumId,

            @Schema(description = "대표 썸네일 이미지 URL. 잠금 전 앨범은 썸네일이 없으면 null", example = "https://cdn.widyu.shop/albums/thumb_10.jpg")
            String thumbnailUrl,

            @Schema(description = "앨범 잠금 해제 여부. 보호자가 올리고 아직 해금하지 않은 앨범이면 false", example = "true")
            boolean isUnlocked
    ) {
        public static AlbumInfo from(Album album, boolean isUnlocked) {
            if (!album.getThumbnailUrls().isEmpty()) {
                return new AlbumInfo(album.getId(), album.getThumbnailUrls().getFirst(), isUnlocked);
            }
            // 썸네일 없는 사진 게시글 폴백. 잠금 전 앨범은 원본 URL로 대신하지 않는다(#813)
            if (isUnlocked && !album.getMediaUrls().isEmpty()) {
                return new AlbumInfo(album.getId(), album.getMediaUrls().getFirst(), isUnlocked);
            }
            return new AlbumInfo(album.getId(), null, isUnlocked);
        }
    }

    public record HealthScheduleInfo(
            @Schema(description = "일정 이름", example = "내과 정기검진")
            String scheduleName,

            @Schema(description = "남은 일수 (D-day)", example = "14")
            Integer dday,

            @Schema(description = "일정 일시", example = "2026-05-28T17:00:00")
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
            LocalDateTime scheduledAt,

            @Schema(description = "장소 주소", example = "서울특별시 성북구 고려대로 73")
            String placeAddress,

            @Schema(description = "위도", example = "37.5894")
            Double latitude,

            @Schema(description = "경도", example = "127.0327")
            Double longitude
    ) {
        public static HealthScheduleInfo from(HealthSchedule schedule, LocalDate today) {
            int dday = (int) ChronoUnit.DAYS.between(today, schedule.getScheduledAt().toLocalDate());
            return new HealthScheduleInfo(
                    schedule.getScheduleName(),
                    dday,
                    schedule.getScheduledAt(),
                    schedule.getPlaceAddress(),
                    schedule.getLatitude(),
                    schedule.getLongitude()
            );
        }
    }

    public record WalkInfo(
            @Schema(description = "오늘 실제 걸음 수", example = "4200")
            Integer actual,

            @Schema(description = "목표 걸음 수", example = "10000")
            Integer goal
    ) {
        public static WalkInfo from(Walk walk) {
            return new WalkInfo(walk.getActualSteps(), walk.getGoalSteps());
        }

        public static WalkInfo withDefaultGoal(int defaultGoal) {
            return new WalkInfo(0, defaultGoal);
        }
    }
}
