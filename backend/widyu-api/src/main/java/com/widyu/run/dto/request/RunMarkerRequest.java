package com.widyu.run.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 마커 등록(정책 1.8.2 필수 항목). {@code markerId}는 불변이고 같은 id의 재등록은 멱등이다.
 * 마커 종류 값 목록은 연구 프로토콜이 정하므로(미정) 검증하지 않는다.
 */
public record RunMarkerRequest(
        @NotNull(message = "마커 ID는 필수입니다.")
        @Pattern(regexp = "^[a-z0-9._-]{1,64}$", message = "마커 ID는 소문자·숫자·._- 1~64자여야 합니다.")
        String markerId,

        @NotNull(message = "마커 종류는 필수입니다.")
        @Size(max = 30, message = "마커 종류는 30자 이하여야 합니다.")
        String kind,

        @Size(max = 100, message = "라벨은 100자 이하여야 합니다.")
        String label,

        @NotNull(message = "마커 경과 시각은 필수입니다.")
        @Pattern(regexp = "^[0-9]{1,19}$", message = "마커 경과 시각은 10진 정수 문자열이어야 합니다.")
        String sourceElapsedNs,

        @NotNull(message = "마커 시각은 필수입니다.")
        @PositiveOrZero(message = "마커 시각은 0 이상이어야 합니다.")
        Long tsMs,

        @NotNull(message = "마커 출처는 필수입니다.")
        @Pattern(regexp = "OPERATOR_APP|PARTICIPANT|AUTO",
                message = "마커 출처는 OPERATOR_APP, PARTICIPANT, AUTO 중 하나여야 합니다.")
        String source,

        @NotNull(message = "출처 기기 ID는 필수입니다.")
        @Pattern(regexp = "^[a-z0-9._-]{1,64}$", message = "출처 기기 ID는 소문자·숫자·._- 1~64자여야 합니다.")
        String sourceDeviceId,

        @NotNull(message = "시계 정보는 필수입니다.")
        @Valid
        MarkerClockRequest clock
) {

    public static RunMarkerRequest of(
            String markerId,
            String kind,
            String label,
            String sourceElapsedNs,
            Long tsMs,
            String source,
            String sourceDeviceId,
            MarkerClockRequest clock
    ) {
        return new RunMarkerRequest(
                markerId, kind, label, sourceElapsedNs, tsMs, source, sourceDeviceId, clock);
    }

    /** 누른 기기의 시계 매핑. 센서 배치와 같은 규칙으로 `clock_mapping`에 등록한다(정책 1.8.3). */
    public record MarkerClockRequest(
            @NotNull(message = "부팅 식별자는 필수입니다.")
            @Size(max = 64, message = "부팅 식별자는 64자 이하여야 합니다.")
            String bootId,

            @NotNull(message = "시계 매핑 ID는 필수입니다.")
            @Pattern(regexp = "^[a-z0-9._-]{1,64}$", message = "시계 매핑 ID 형식이 올바르지 않습니다.")
            String clockMappingId,

            @NotNull(message = "기준점 경과 시각은 필수입니다.")
            @Pattern(regexp = "^[0-9]{1,19}$", message = "기준점 경과 시각은 10진 정수 문자열이어야 합니다.")
            String anchorElapsedNs,

            @NotNull(message = "기준점 벽시계 시각은 필수입니다.")
            Long anchorEpochMs,

            @NotNull(message = "불확실성은 필수입니다.")
            @PositiveOrZero(message = "불확실성은 0 이상이어야 합니다.")
            Double uncertaintyMs
    ) {

        public static MarkerClockRequest of(
                String bootId,
                String clockMappingId,
                String anchorElapsedNs,
                Long anchorEpochMs,
                Double uncertaintyMs
        ) {
            return new MarkerClockRequest(
                    bootId, clockMappingId, anchorElapsedNs, anchorEpochMs, uncertaintyMs);
        }
    }
}
