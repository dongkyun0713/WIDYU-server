package com.widyu.location.realtime.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * 위치 갱신 페이로드(LLD-0048 3절). v2는 부록 B의 snake_case 필드를 함께 싣고,
 * 기존 앱은 {@code memberId·latitude·longitude·timestamp} 4필드만 보낸다(하위 호환).
 *
 * <p>좌표는 {@code lat}/{@code lon}이 오면 그것을, 없으면 {@code latitude}/{@code longitude}를 쓴다.
 * 좌표 필수 검사는 이 결정 뒤에 해야 하므로 Bean Validation이 아니라 서비스가 한다.
 */
public record LocationUpdateRequest(
    @NotNull(message = "멤버 ID는 필수입니다")
    Long memberId,

    Double latitude,

    Double longitude,

    Long timestamp,  // 클라이언트에서 측정한 시각 (optional)

    @JsonProperty("v") Integer v,

    @JsonProperty("device_id") String deviceId,

    @JsonProperty("session_id") String sessionId,

    @JsonProperty("seq") Long seq,

    @JsonProperty("study_id") String studyId,

    @JsonProperty("participation_id") String participationId,

    @JsonProperty("run_id") String runId,

    @JsonProperty("lat") Double lat,

    @JsonProperty("lon") Double lon,

    @JsonProperty("accuracy_m") Double accuracyM,

    @JsonProperty("speed_mps") Double speedMps,

    @JsonProperty("speed_accuracy_mps") Double speedAccuracyMps,

    @JsonProperty("heading_deg") Double headingDeg,

    @JsonProperty("altitude_m") Double altitudeM,

    @JsonProperty("provider") String provider,

    @JsonProperty("is_mock") Boolean isMock,

    @JsonProperty("ts_ms") Long tsMs,

    @JsonProperty("reason") String reason
) {

    public static LocationUpdateRequest of(Long memberId, Double latitude, Double longitude, Long timestamp) {
        return new LocationUpdateRequest(
                memberId, latitude, longitude, timestamp,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    public Double resolvedLatitude() {
        if (lat != null) {
            return lat;
        }
        return latitude;
    }

    public Double resolvedLongitude() {
        if (lon != null) {
            return lon;
        }
        return longitude;
    }

    /** 원본 저장 대상인가(LLD-0048 3절). v2를 선언하고 기기와 잰 시각이 있어야 한다. */
    public boolean isRawFix() {
        return v != null && v == 2 && deviceId != null && tsMs != null;
    }
}
