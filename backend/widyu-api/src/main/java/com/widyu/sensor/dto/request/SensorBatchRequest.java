package com.widyu.sensor.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * v2 IMU 배치(지시서 부록 A). 서버는 원문 바이트를 그대로 보관하므로 모르는 필드를 거절하지 않는다
 * (ADR-0030 v2 결정 3). 나노초 필드는 64비트 정밀도를 잃지 않도록 10진 문자열로 받는다.
 *
 * <p>축(`acc`·`gyro`) 구조는 Bean Validation으로 캐스케이드하지 않는다. 축 위반은
 * `SENSOR_SAMPLE_INVALID`, 그 밖은 `SENSOR_BATCH_INVALID`로 갈라야 해서 서비스에서 직접 검사한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SensorBatchRequest(
        @NotNull(message = "형식 버전은 필수입니다.")
        Integer v,

        @NotNull(message = "스트림은 필수입니다.")
        @Pattern(regexp = "imu_watch|imu_phone", message = "스트림은 imu_watch 또는 imu_phone이어야 합니다.")
        String stream,

        @NotNull(message = "출처는 필수입니다.")
        @Pattern(regexp = "watch|phone", message = "출처는 watch 또는 phone이어야 합니다.")
        String source,

        @JsonProperty("batch_id")
        @NotNull(message = "배치 ID는 필수입니다.")
        @Pattern(regexp = "^[a-z0-9]{26}$", message = "배치 ID는 소문자 ULID 26자여야 합니다.")
        String batchId,

        @JsonProperty("device_id")
        @NotNull(message = "기기 ID는 필수입니다.")
        @Pattern(regexp = "^[a-z0-9._-]{1,64}$", message = "기기 ID는 소문자·숫자·._- 1~64자여야 합니다.")
        String deviceId,

        @JsonProperty("session_id")
        @NotNull(message = "세션 ID는 필수입니다.")
        @Pattern(regexp = "^[a-z0-9._-]{1,64}$", message = "세션 ID는 소문자·숫자·._- 1~64자여야 합니다.")
        String sessionId,

        @NotNull(message = "배치 순번은 필수입니다.")
        @PositiveOrZero(message = "배치 순번은 0 이상이어야 합니다.")
        Long seq,

        @JsonProperty("study_id")
        @Size(max = 64, message = "연구 ID는 64자 이하여야 합니다.")
        String studyId,

        @JsonProperty("participation_id")
        @Size(max = 64, message = "참여 ID는 64자 이하여야 합니다.")
        String participationId,

        @JsonProperty("run_id")
        @Size(max = 64, message = "회차 ID는 64자 이하여야 합니다.")
        String runId,

        @NotNull(message = "시계 정보는 필수입니다.")
        @Valid
        Clock clock,

        Axis acc,

        Axis gyro,

        @Valid
        Trigger trigger,

        @JsonProperty("gyro_backfill")
        Boolean gyroBackfill,

        @JsonProperty("backfill_for")
        JsonNode backfillFor,

        @Valid
        Resend resend,

        @JsonProperty("collection_mode")
        @NotNull(message = "수집 모드는 필수입니다.")
        @Pattern(regexp = "product|research", message = "수집 모드는 product 또는 research여야 합니다.")
        String collectionMode,

        @JsonProperty("gyro_mode")
        @NotNull(message = "자이로 모드는 필수입니다.")
        @Pattern(regexp = "continuous|trigger", message = "자이로 모드는 continuous 또는 trigger여야 합니다.")
        String gyroMode,

        @JsonProperty("on_body")
        @NotNull(message = "착용 여부는 필수입니다.")
        Boolean onBody,

        @JsonProperty("wear_state")
        @Pattern(regexp = "WORN_VALID|WORN_INVALID|PLANNED_NOT_WORN|ACTUAL_NOT_WORN|UNKNOWN",
                message = "착용 상태 값이 올바르지 않습니다.")
        String wearState,

        @JsonProperty("missing_reason")
        @Size(max = 64, message = "결측 사유는 64자 이하여야 합니다.")
        String missingReason,

        @JsonProperty("watch_battery_pct")
        @Min(value = 0, message = "배터리 잔량은 0 이상이어야 합니다.")
        @Max(value = 100, message = "배터리 잔량은 100 이하여야 합니다.")
        Integer watchBatteryPct,

        @JsonProperty("phone_received_at_ms")
        Long phoneReceivedAtMs
) {

    /** 시계 환산 다섯 값(정책 1.1.7). 받은 그대로 보존한다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Clock(
            @JsonProperty("boot_id")
            @NotBlank(message = "부팅 식별자는 필수입니다.")
            @Size(max = 64, message = "부팅 식별자는 64자 이하여야 합니다.")
            String bootId,

            @JsonProperty("clock_mapping_id")
            @NotNull(message = "시계 매핑 ID는 필수입니다.")
            @Pattern(regexp = "^[a-z0-9._-]{1,64}$", message = "시계 매핑 ID 형식이 올바르지 않습니다.")
            String clockMappingId,

            @JsonProperty("anchor_elapsed_ns")
            @NotNull(message = "기준점 경과 시각은 필수입니다.")
            String anchorElapsedNs,

            @JsonProperty("anchor_epoch_ms")
            @NotNull(message = "기준점 벽시계 시각은 필수입니다.")
            Long anchorEpochMs,

            @JsonProperty("uncertainty_ms")
            @NotNull(message = "불확실성은 필수입니다.")
            @PositiveOrZero(message = "불확실성은 0 이상이어야 합니다.")
            Double uncertaintyMs
    ) {}

    /**
     * 가속도·자이로 축. 값 배열 이름만 다르다(가속도 {@code mg}, 자이로 {@code mrads}).
     * 두 축은 시간축이 독립이라 샘플 수가 달라도 된다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Axis(
            @JsonProperty("fs_hz_requested") Double fsHzRequested,
            Integer n,
            @JsonProperty("t0_elapsed_ns") String t0ElapsedNs,
            @JsonProperty("dt_ns") List<Long> dtNs,
            List<List<Integer>> mg,
            List<List<Integer>> mrads
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Trigger(
            String kind,
            @JsonProperty("smv_g") Double smvG,
            @JsonProperty("event_elapsed_ns") String eventElapsedNs,
            @JsonProperty("ts_ms") Long tsMs
    ) {}

    /** 재전송 계보 6필드(지시서 B4, 검사기 C12). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Resend(
            @JsonProperty("is_resend") Boolean isResend,
            @JsonProperty("original_batch_id") String originalBatchId,
            @JsonProperty("original_seq") Long originalSeq,
            @JsonProperty("original_run_id") String originalRunId,
            @JsonProperty("original_session_id") String originalSessionId,
            @JsonProperty("resent_at_ms") Long resentAtMs
    ) {}
}
