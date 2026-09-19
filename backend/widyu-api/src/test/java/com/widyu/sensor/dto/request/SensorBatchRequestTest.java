package com.widyu.sensor.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SensorBatchRequest 역직렬화 단위 테스트")
class SensorBatchRequestTest {

    private static final String APPENDIX_A_BATCH = """
            {
              "v": 2,
              "stream": "imu_watch",
              "source": "watch",
              "batch_id": "01j8zk3v9x2q4m7n8p1r5s6t7u",
              "device_id": "gw-3f2a",
              "session_id": "s-20260919-01",
              "seq": 88213,
              "study_id": null,
              "participation_id": null,
              "run_id": null,
              "clock": {
                "boot_id": "b7c1",
                "clock_mapping_id": "cm-01",
                "anchor_elapsed_ns": "993847100000001",
                "anchor_epoch_ms": 1760000000000,
                "uncertainty_ms": 2.0
              },
              "acc": {
                "fs_hz_requested": 50,
                "n": 2,
                "t0_elapsed_ns": "993847112340001",
                "dt_ns": [19998417],
                "mg": [[20, -980, 110], [22, -979, 108]]
              },
              "gyro": null,
              "trigger": {
                "kind": "impact",
                "smv_g": 3.2,
                "event_elapsed_ns": "993849330000001",
                "ts_ms": 1760000000600
              },
              "gyro_backfill": false,
              "backfill_for": null,
              "resend": null,
              "collection_mode": "product",
              "gyro_mode": "continuous",
              "on_body": true,
              "wear_state": null,
              "missing_reason": null,
              "watch_battery_pct": 63
            }""";

    @Test
    @DisplayName("부록 A의 snake_case 배치를 역직렬화하면 모든 필드가 그대로 매핑된다")
    void 부록_A의_snake_case_배치를_역직렬화하면_모든_필드가_그대로_매핑된다() throws Exception {
        // given
        ObjectMapper objectMapper = new ObjectMapper();

        // when
        SensorBatchRequest request = objectMapper.readValue(APPENDIX_A_BATCH, SensorBatchRequest.class);

        // then
        assertThat(request.v()).isEqualTo(2);
        assertThat(request.stream()).isEqualTo("imu_watch");
        assertThat(request.source()).isEqualTo("watch");
        assertThat(request.batchId()).isEqualTo("01j8zk3v9x2q4m7n8p1r5s6t7u");
        assertThat(request.deviceId()).isEqualTo("gw-3f2a");
        assertThat(request.sessionId()).isEqualTo("s-20260919-01");
        assertThat(request.seq()).isEqualTo(88213L);
        assertThat(request.collectionMode()).isEqualTo("product");
        assertThat(request.gyroMode()).isEqualTo("continuous");
        assertThat(request.onBody()).isTrue();
        assertThat(request.watchBatteryPct()).isEqualTo(63);
        assertThat(request.gyroBackfill()).isFalse();
        // 자이로 부재는 null 그대로 남는다(0 배열로 바뀌지 않는다).
        assertThat(request.gyro()).isNull();
        assertThat(request.clock().bootId()).isEqualTo("b7c1");
        assertThat(request.clock().clockMappingId()).isEqualTo("cm-01");
        assertThat(request.clock().anchorEpochMs()).isEqualTo(1_760_000_000_000L);
        assertThat(request.clock().uncertaintyMs()).isEqualTo(2.0);
        assertThat(request.acc().n()).isEqualTo(2);
        assertThat(request.acc().dtNs()).containsExactly(19_998_417L);
        assertThat(request.acc().mg()).containsExactly(java.util.List.of(20, -980, 110), java.util.List.of(22, -979, 108));
        assertThat(request.trigger().kind()).isEqualTo("impact");
        assertThat(request.trigger().smvG()).isEqualTo(3.2);
        assertThat(request.trigger().tsMs()).isEqualTo(1_760_000_000_600L);
    }

    @Test
    @DisplayName("나노초 시각을 역직렬화하면 문자열 그대로 남아 64비트 정밀도를 잃지 않는다")
    void 나노초_시각을_역직렬화하면_문자열_그대로_남아_64비트_정밀도를_잃지_않는다() throws Exception {
        // given
        ObjectMapper objectMapper = new ObjectMapper();

        // when
        SensorBatchRequest request = objectMapper.readValue(APPENDIX_A_BATCH, SensorBatchRequest.class);

        // then
        assertThat(request.clock().anchorElapsedNs()).isEqualTo("993847100000001");
        assertThat(request.acc().t0ElapsedNs()).isEqualTo("993847112340001");
        assertThat(request.trigger().eventElapsedNs()).isEqualTo("993849330000001");
        // double로 변환됐다면 마지막 자리가 살아남지 못한다.
        assertThat(Long.parseLong(request.clock().anchorElapsedNs())).isEqualTo(993_847_100_000_001L);
    }
}
