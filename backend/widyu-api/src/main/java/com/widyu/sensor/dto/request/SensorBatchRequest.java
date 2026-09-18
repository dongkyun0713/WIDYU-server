package com.widyu.sensor.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.widyu.sensor.GyroMode;
import com.widyu.sensor.SensorBatchKind;
import com.widyu.sensor.SensorStreamType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 원시 센서 배치 1건(LLD-0041 3.1). 모르는 최상위 필드는 조용한 손실을 막기 위해 거절한다.
 * 샘플 내부 스키마는 스트림별로 고정하되 서버는 정수 {@code t}만 검증한다.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SensorBatchRequest(
        @NotNull(message = "기기 ID는 필수입니다.")
        // DB collation이 case-insensitive라 watch-A와 watch-a가 UK에서 같지만 S3 키는 달라진다.
        @Pattern(regexp = "^[a-z0-9._-]{1,64}$",
                message = "기기 ID는 소문자·숫자·._- 1~64자여야 합니다.")
        String deviceId,

        @NotNull(message = "세션 ID는 필수입니다.")
        @Pattern(regexp = "^[a-z0-9._-]{1,64}$",
                message = "세션 ID는 소문자·숫자·._- 1~64자여야 합니다.")
        String sessionId,

        @NotNull(message = "배치 순번은 필수입니다.")
        @PositiveOrZero(message = "배치 순번은 0 이상이어야 합니다.")
        Long seq,

        @NotNull(message = "스트림 종류는 필수입니다.")
        SensorStreamType streamType,

        @NotNull(message = "배치 종류는 필수입니다.")
        SensorBatchKind batchKind,

        @NotNull(message = "자이로 모드는 필수입니다.")
        GyroMode gyroMode,

        Boolean onBody,

        @NotEmpty(message = "샘플은 1개 이상이어야 합니다.")
        @Size(max = 1000, message = "샘플은 1000개 이하여야 합니다.")
        List<JsonNode> samples
) {

    /**
     * 모르는 최상위 필드를 조용히 버리지 않고 거절한다(ADR-0030 결정 3).
     * Boot 기본 ObjectMapper는 {@code FAIL_ON_UNKNOWN_PROPERTIES}가 꺼져 있어
     * {@code @JsonIgnoreProperties(ignoreUnknown = false)}만으로는 거절되지 않는다.
     * REST와 WebSocket(#632)이 같은 DTO를 쓰므로 거절 지점을 DTO에 둔다.
     * 값은 메시지에 담지 않는다(정책 1.6.7).
     */
    @JsonAnySetter
    void rejectUnknownField(String name, Object ignoredValue) {
        throw new IllegalArgumentException("알 수 없는 필드입니다: " + name);
    }

    public static SensorBatchRequest of(
            String deviceId,
            String sessionId,
            Long seq,
            SensorStreamType streamType,
            SensorBatchKind batchKind,
            GyroMode gyroMode,
            Boolean onBody,
            List<JsonNode> samples
    ) {
        return new SensorBatchRequest(
                deviceId, sessionId, seq, streamType, batchKind, gyroMode, onBody, samples);
    }
}
