package com.widyu.sensor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.sensor.SensorBatch;
import com.widyu.sensor.application.SensorWindowCsv.Window;
import com.widyu.sensor.dto.request.HeartRateBatchRequest;
import com.widyu.sensor.dto.request.SensorBatchRequest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SensorWindowCsvTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("IMU 표본은 기준점으로 epoch ms를 환산하고 구간 안 표본만 phase와 함께 남긴다")
    void IMU_표본은_기준점으로_시각을_환산하고_구간_안만_남긴다() throws Exception {
        // given: 기준점 경과 1초 = epoch 1000ms, 가속도 20ms 간격 세 표본(1000·1020·1040ms)
        SensorBatch batch = batch("b1", "imu_watch");
        SensorBatchRequest request = objectMapper.readValue("""
                {"batch_id":"b1","stream":"imu_watch",
                 "clock":{"anchor_elapsed_ns":"1000000000","anchor_epoch_ms":1000},
                 "acc":{"n":3,"t0_elapsed_ns":"1000000000","dt_ns":[20000000,20000000],
                        "mg":[[1,2,3],[4,5,6],[7,8,9]]}}
                """, SensorBatchRequest.class);
        Window window = new Window(1030, 1035, 1010, 1040);

        // when
        List<String> rows = SensorWindowCsv.imuRows(batch, request, window);

        // then
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).startsWith("before,").contains(",1020,watch-1,imu_watch,b1,acc,4,5,6,mg");
        assertThat(rows.get(1)).startsWith("after,").contains(",1040,watch-1,imu_watch,b1,acc,7,8,9,mg");
    }

    @Test
    @DisplayName("IMU 축의 간격 수가 표본 수와 맞지 않으면 원문 손상으로 거부한다")
    void IMU_축_구조가_깨지면_거부한다() throws Exception {
        // given
        SensorBatch batch = batch("b1", "imu_watch");
        SensorBatchRequest request = objectMapper.readValue("""
                {"batch_id":"b1","stream":"imu_watch",
                 "clock":{"anchor_elapsed_ns":"0","anchor_epoch_ms":0},
                 "gyro":{"n":2,"t0_elapsed_ns":"0","dt_ns":[],"mrads":[[1,1,1],[2,2,2]]}}
                """, SensorBatchRequest.class);

        // when & then
        assertThatThrownBy(() -> SensorWindowCsv.imuRows(batch, request, new Window(0, 10, 0, 10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("심박 표본은 구간 밖을 버리고 양 끝 시각은 포함한다")
    void 심박_표본은_구간_양_끝을_포함한다() throws Exception {
        // given
        SensorBatch batch = batch("h1", "hr");
        HeartRateBatchRequest request = objectMapper.readValue("""
                {"batch_id":"h1","stream":"hr","samples":[
                  {"bpm":70,"ts_ms":999,"accuracy":"HIGH"},
                  {"bpm":71,"ts_ms":1000,"accuracy":"HIGH"},
                  {"bpm":72,"ts_ms":2000,"accuracy":"LOW"},
                  {"bpm":73,"ts_ms":2001,"accuracy":"HIGH"}]}
                """, HeartRateBatchRequest.class);

        // when
        List<String> rows = SensorWindowCsv.heartRows(batch, request, new Window(1500, 1600, 1000, 2000));

        // then
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).startsWith("before,").endsWith(",1000,watch-1,h1,71,HIGH\n");
        assertThat(rows.get(1)).startsWith("after,").endsWith(",2000,watch-1,h1,72,LOW\n");
    }

    @Test
    @DisplayName("원문 묶음 ID가 목록과 다르면 다른 묶음의 원문으로 보고 거부한다")
    void 원문_묶음_ID가_다르면_거부한다() throws Exception {
        // given
        SensorBatch batch = batch("h1", "hr");
        HeartRateBatchRequest request = objectMapper.readValue(
                "{\"batch_id\":\"other\",\"stream\":\"hr\",\"samples\":[]}", HeartRateBatchRequest.class);

        // when & then
        assertThatThrownBy(() -> SensorWindowCsv.heartRows(batch, request, new Window(0, 1, 0, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("쉼표·따옴표가 든 값은 CSV 규칙대로 감싸고 null은 빈 칸으로 쓴다")
    void CSV_값을_규칙대로_감싼다() {
        // when
        String line = SensorWindowCsv.line("a,b", "say \"hi\"", null, 3);

        // then
        assertThat(line).isEqualTo("\"a,b\",\"say \"\"hi\"\"\",,3\n");
    }

    @Test
    @DisplayName("수식으로 읽힐 문자열은 작은따옴표를 붙이고 음수 숫자는 그대로 쓴다")
    void 수식으로_읽힐_문자열만_막는다() {
        // when
        String line = SensorWindowCsv.line("=1+1", "@SUM(A1)", -37.5, -12);

        // then
        assertThat(line).isEqualTo("'=1+1,'@SUM(A1),-37.5,-12\n");
    }

    @Test
    @DisplayName("epoch ms를 한국 시간 문자열로 바꾼다")
    void epoch_ms를_한국_시간으로_바꾼다() {
        // when & then: 2026-10-30T00:10:00Z = 09:10 KST
        assertThat(SensorWindowCsv.kst(1_793_319_000_000L)).isEqualTo("2026-10-30 09:10:00.000");
    }

    private SensorBatch batch(String batchId, String stream) {
        SensorBatch batch = mock(SensorBatch.class);
        given(batch.getBatchId()).willReturn(batchId);
        given(batch.getDeviceId()).willReturn("watch-1");
        given(batch.getStream()).willReturn(stream);
        return batch;
    }
}
