package com.widyu.sensor.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.sensor.GyroMode;
import com.widyu.sensor.SensorBatchKind;
import com.widyu.sensor.SensorStreamType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SensorBatchRequest 역직렬화 단위 테스트")
class SensorBatchRequestTest {

    private static final String VALID_BATCH_JSON = """
            {
              "deviceId": "watch-3f2a",
              "sessionId": "s-20260918-01",
              "seq": 1234,
              "streamType": "WATCH_ACCEL",
              "batchKind": "LIVE",
              "gyroMode": "CONTINUOUS",
              "onBody": true,
              "samples": [
                { "t": 1758150000123, "x": -12, "y": 980, "z": 45 },
                { "t": 1758150000143, "x": -10, "y": 982, "z": 44 }
              ]
            }
            """;

    private static final String BATCH_JSON_WITH_UNKNOWN_FIELD = """
            {
              "deviceId": "watch-3f2a",
              "sessionId": "s-20260918-01",
              "seq": 1234,
              "streamType": "WATCH_ACCEL",
              "batchKind": "LIVE",
              "gyroMode": "CONTINUOUS",
              "onBody": true,
              "extra": 1,
              "samples": [
                { "t": 1758150000123, "x": -12, "y": 980, "z": 45 },
                { "t": 1758150000143, "x": -10, "y": 982, "z": 44 }
              ]
            }
            """;

    @Test
    @DisplayName("모르는 최상위 필드가 있으면 역직렬화에서 예외가 발생한다")
    void 모르는_최상위_필드가_있으면_역직렬화에서_예외가_발생한다() throws Exception {
        // given
        // Boot 기본값과 같이 전역 FAIL_ON_UNKNOWN_PROPERTIES를 꺼도 DTO 애노테이션이 이겨야 한다.
        ObjectMapper objectMapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        // when & then
        assertThatThrownBy(() ->
                objectMapper.readValue(BATCH_JSON_WITH_UNKNOWN_FIELD, SensorBatchRequest.class))
                .isInstanceOf(JsonMappingException.class)
                .hasMessageContaining("extra");
    }

    @Test
    @DisplayName("모르는 필드가 없는 배치를 역직렬화하면 모든 필드가 그대로 복원된다")
    void 모르는_필드가_없는_배치를_역직렬화하면_모든_필드가_그대로_복원된다() throws Exception {
        // given
        ObjectMapper objectMapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        // when
        SensorBatchRequest request = objectMapper.readValue(VALID_BATCH_JSON, SensorBatchRequest.class);

        // then
        assertThat(request.deviceId()).isEqualTo("watch-3f2a");
        assertThat(request.sessionId()).isEqualTo("s-20260918-01");
        assertThat(request.seq()).isEqualTo(1234L);
        assertThat(request.streamType()).isEqualTo(SensorStreamType.WATCH_ACCEL);
        assertThat(request.batchKind()).isEqualTo(SensorBatchKind.LIVE);
        assertThat(request.gyroMode()).isEqualTo(GyroMode.CONTINUOUS);
        assertThat(request.onBody()).isTrue();
        assertThat(request.samples()).hasSize(2);
        assertThat(request.samples().getFirst().get("t").asLong()).isEqualTo(1758150000123L);
    }
}
