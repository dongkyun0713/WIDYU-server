package com.widyu.sensor.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * v2 심박 배치(지시서 부록 B `hr`, LLD-0047 3절). IMU 배치와 같은 endpoint로 오고 {@code stream}으로 갈린다.
 * 서버는 원문 바이트를 그대로 보관하므로 모르는 필드를 거절하지 않는다.
 *
 * <p>{@code samples} 검증은 Bean Validation으로 캐스케이드하지 않는다. 샘플 위반은
 * {@code SENSOR_SAMPLE_INVALID}, 그 밖은 {@code SENSOR_BATCH_INVALID}로 갈라야 해서 서비스가 직접 본다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HeartRateBatchRequest(
        @NotNull(message = "형식 버전은 필수입니다.")
        Integer v,

        @NotNull(message = "스트림은 필수입니다.")
        @Pattern(regexp = "hr", message = "심박 배치의 스트림은 hr이어야 합니다.")
        String stream,

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
        SensorBatchRequest.Clock clock,

        @NotNull(message = "샘플은 필수입니다.")
        List<Sample> samples,

        @JsonProperty("on_body")
        @NotNull(message = "착용 여부는 필수입니다.")
        Boolean onBody,

        @JsonProperty("watch_battery_pct")
        @Min(value = 0, message = "배터리 잔량은 0 이상이어야 합니다.")
        @Max(value = 100, message = "배터리 잔량은 100 이하여야 합니다.")
        Integer watchBatteryPct,

        @Valid
        SensorBatchRequest.Resend resend,

        @JsonProperty("phone_received_at_ms")
        Long phoneReceivedAtMs,

        /** 계약에서 삭제된 필드(검사기 E6). 실려 오면 거부한다. */
        JsonNode location,

        /** 계약에서 삭제된 필드(검사기 E6). AI 상황값은 서버가 정한다(#477). */
        JsonNode context
) {

    /** 심박 샘플 1건. {@code bpm == 0}은 {@code accuracy == UNRELIABLE}일 때만 허용한다(검사기 E7). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sample(
            Integer bpm,
            @JsonProperty("ts_ms") Long tsMs,
            String accuracy
    ) {

        public static Sample of(Integer bpm, Long tsMs, String accuracy) {
            return new Sample(bpm, tsMs, accuracy);
        }
    }
}
