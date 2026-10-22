package com.widyu.home.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.widyu.album.Album;
import com.widyu.healthschedule.HealthSchedule;
import com.widyu.heart.HeartRateResult;
import com.widyu.heart.HeartRateStatus;
import com.widyu.medicine.MedicineSchedule;
import com.widyu.walk.Walk;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public record GuardianHomeCardsResponse(

        @Schema(description = "외출 여부 (true=외출 중, false=집, null=최근 위치 없음)", example = "true")
        Boolean isOuting,

        @Schema(description = "심박수 · 착용 상태")
        HeartRateInfo heartRate,

        @Schema(description = "약 복용 카드")
        MedicineInfo medicine,

        @Schema(description = "추억 앨범 카드 (최대 3장, 점수 기반 추천)")
        List<AlbumInfo> albums,

        @Schema(description = "건강달력 카드 (가장 가까운 일정)")
        HealthScheduleInfo healthSchedule,

        @Schema(description = "걷기 카드")
        WalkInfo walk
) {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final long WEARING_THRESHOLD_SECONDS = 30;

    public static GuardianHomeCardsResponse of(
            Boolean isOuting,
            HeartRateInfo heartRate,
            MedicineInfo medicine,
            List<AlbumInfo> albums,
            HealthScheduleInfo healthSchedule,
            WalkInfo walk
    ) {
        return new GuardianHomeCardsResponse(isOuting, heartRate, medicine, albums, healthSchedule, walk);
    }

    public record HeartRateInfo(
            @Schema(description = "워치 착용 여부 (최근 30초 이내 심박 데이터 수신 시 true)", example = "true")
            boolean isWearing,

            @Schema(description = "심박수 상태", example = "NORMAL")
            HeartRateStatus heartRateStatus,

            @Schema(description = "심박수 BPM, 미착용 시 null", example = "103")
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

    @Schema(name = "GuardianHomeMedicineInfo")
    public record MedicineInfo(
            @Schema(description = "오늘 총 복용 예정 횟수 (동그라미 개수)", example = "6")
            Integer totalCount,

            @Schema(description = "가장 최근 복용 인증 사진 URL, 없으면 null")
            String latestProofImageUrl,

            @Schema(description = "스케줄별 복용 상태 (동그라미 색상용, 복용 시간 오름차순)")
            List<ScheduleStatus> scheduleStatuses
    ) {
        public static MedicineInfo of(
                Integer totalCount,
                String latestProofImageUrl,
                List<ScheduleStatus> scheduleStatuses
        ) {
            return new MedicineInfo(totalCount, latestProofImageUrl, scheduleStatuses);
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

            @Schema(description = "대표 썸네일 이미지 URL", example = "https://cdn.widyu.shop/albums/thumb_10.jpg")
            String thumbnailUrl
    ) {
        public static AlbumInfo from(Album album) {
            if (!album.getThumbnailUrls().isEmpty()) {
                return new AlbumInfo(album.getId(), album.getThumbnailUrls().getFirst());
            }
            if (!album.getMediaUrls().isEmpty()) {
                return new AlbumInfo(album.getId(), album.getMediaUrls().getFirst()); // 썸네일 없는 사진 게시글 폴백
            }
            return new AlbumInfo(album.getId(), null);
        }
    }

    @Schema(name = "GuardianHomeHealthScheduleInfo")
    public record HealthScheduleInfo(
            @Schema(description = "건강 일정 ID", example = "3")
            Long scheduleId,

            @Schema(description = "일정 이름", example = "내과 정기검진")
            String scheduleName,

            @Schema(description = "일정 일시", example = "2026-06-16T14:00:00")
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
            LocalDateTime scheduledAt,

            @Schema(description = "장소 주소", example = "서울특별시 성북구 고려대로 73")
            String placeAddress,

            @Schema(description = "위도", example = "37.5894")
            Double latitude,

            @Schema(description = "경도", example = "127.0327")
            Double longitude
    ) {
        public static HealthScheduleInfo from(HealthSchedule schedule) {
            return new HealthScheduleInfo(
                    schedule.getId(),
                    schedule.getScheduleName(),
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
