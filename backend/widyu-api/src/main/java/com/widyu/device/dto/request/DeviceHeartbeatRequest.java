package com.widyu.device.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 기기 상태 하트비트(지시서 부록 B, LLD-0049 3절). 폰이 60초마다 보낸다.
 * 서버가 원문 바이트를 그대로 보관하므로 모르는 필드는 거절하지 않는다(ADR-0030 v2).
 *
 * <p>{@code seq}가 없어 멱등 키는 {@code (device_id, session_id, ts_ms)}다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceHeartbeatRequest(
        @NotNull(message = "형식 버전은 필수입니다.")
        Integer v,

        @JsonProperty("ts_ms")
        @NotNull(message = "측정 시각은 필수입니다.")
        Long tsMs,

        @JsonProperty("device_id")
        @NotNull(message = "기기 ID는 필수입니다.")
        @Pattern(regexp = "^[a-z0-9._-]{1,64}$", message = "기기 ID는 소문자·숫자·._- 1~64자여야 합니다.")
        String deviceId,

        @JsonProperty("session_id")
        @NotNull(message = "세션 ID는 필수입니다.")
        @Pattern(regexp = "^[a-z0-9._-]{1,64}$", message = "세션 ID는 소문자·숫자·._- 1~64자여야 합니다.")
        String sessionId,

        @JsonProperty("study_id")
        @Size(max = 64, message = "연구 ID는 64자 이하여야 합니다.")
        String studyId,

        @JsonProperty("participation_id")
        @Size(max = 64, message = "참여 ID는 64자 이하여야 합니다.")
        String participationId,

        @JsonProperty("run_id")
        @Size(max = 64, message = "회차 ID는 64자 이하여야 합니다.")
        String runId,

        @NotNull(message = "폰 상태는 필수입니다.")
        @Valid
        Phone phone,

        @NotNull(message = "워치 상태는 필수입니다.")
        @Valid
        Watch watch
) {

    /** 폰 상태. 배터리·소켓·큐는 자료 공백의 이유를 가르는 값이라 필수다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Phone(
            @JsonProperty("battery_pct")
            @NotNull(message = "폰 배터리 잔량은 필수입니다.")
            @Min(value = 0, message = "배터리 잔량은 0 이상이어야 합니다.")
            @Max(value = 100, message = "배터리 잔량은 100 이하여야 합니다.")
            Integer batteryPct,

            Boolean charging,

            @Size(max = 40, message = "OS는 40자 이하여야 합니다.")
            String os,

            @JsonProperty("app_ver")
            @Size(max = 20, message = "앱 버전은 20자 이하여야 합니다.")
            String appVer,

            @JsonProperty("socket_connected")
            @NotNull(message = "소켓 연결 여부는 필수입니다.")
            Boolean socketConnected,

            @JsonProperty("location_permission")
            @Size(max = 20, message = "위치 권한은 20자 이하여야 합니다.")
            String locationPermission,

            @JsonProperty("background_restricted")
            Boolean backgroundRestricted,

            @JsonProperty("queue_depth")
            @NotNull(message = "폰 큐 깊이는 필수입니다.")
            @PositiveOrZero(message = "큐 깊이는 0 이상이어야 합니다.")
            Integer queueDepth
    ) {
    }

    /** 워치 상태. {@code connected=false}면 나머지는 null이다 — 모르는 값을 0으로 채우지 않는다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Watch(
            @NotNull(message = "워치 연결 여부는 필수입니다.")
            Boolean connected,

            @JsonProperty("device_id")
            @Size(max = 64, message = "워치 기기 ID는 64자 이하여야 합니다.")
            String deviceId,

            @JsonProperty("battery_pct")
            @Min(value = 0, message = "배터리 잔량은 0 이상이어야 합니다.")
            @Max(value = 100, message = "배터리 잔량은 100 이하여야 합니다.")
            Integer batteryPct,

            @JsonProperty("app_ver")
            @Size(max = 20, message = "앱 버전은 20자 이하여야 합니다.")
            String appVer,

            @JsonProperty("hr_session")
            @Size(max = 20, message = "심박 세션 상태는 20자 이하여야 합니다.")
            String hrSession,

            @JsonProperty("on_body")
            Boolean onBody,

            @JsonProperty("last_hr_ts_ms")
            Long lastHrTsMs,

            @JsonProperty("last_imu_ts_ms")
            Long lastImuTsMs,

            @JsonProperty("queue_depth")
            @PositiveOrZero(message = "큐 깊이는 0 이상이어야 합니다.")
            Integer queueDepth
    ) {
    }
}
