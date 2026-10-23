package com.widyu.sensor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.sensor.SensorBatch;
import com.widyu.sensor.SensorRangeExport;
import com.widyu.sensor.SensorRangeExportStatus;
import com.widyu.sensor.repository.SensorBatchRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
@DisplayName("센서 시간 범위 ZIP 조립")
class SensorRangeExportAssemblerTest {

    @Mock private SensorBatchRepository batchRepository;
    @Mock private S3Service s3Service;
    @TempDir Path workDir;

    @Test
    @DisplayName("경계에 걸친 자이로와 심박 배치를 샘플 시각으로 자르면 구간 안의 값만 남는다")
    void 경계에_걸친_배치를_샘플_시각으로_자르면_구간_안의_값만_남는다() throws Exception {
        // given
        String imuJson = """
                {"v":2,"stream":"imu_watch","batch_id":"imu-a","device_id":"watch-a",
                 "clock":{"anchor_elapsed_ns":"0","anchor_epoch_ms":1000},
                 "gyro":{"n":3,"t0_elapsed_ns":"0","dt_ns":[1000000,1000000],
                         "mrads":[[1,2,3],[4,5,6],[7,8,9]]}}
                """;
        String hrJson = """
                {"v":2,"stream":"hr","batch_id":"hr-a","device_id":"watch-a",
                 "samples":[{"ts_ms":1000,"bpm":60,"accuracy":"VALID"},
                            {"ts_ms":1001,"bpm":61,"accuracy":"VALID"},
                            {"ts_ms":1003,"bpm":63,"accuracy":"VALID"}]}
                """;
        SensorBatch imu = batch(1L, "imu_watch", "imu-a", "imu-key", imuJson);
        SensorBatch hr = batch(2L, "hr", "hr-a", "hr-key", hrJson);
        given(batchRepository.findRangePage(any(), any(), anyLong(), anyLong(),
                anyLong(), anyLong(), any(Pageable.class))).willAnswer(invocation -> {
            String stream = invocation.getArgument(1);
            if ("imu_watch".equals(stream)) {
                return List.of(imu);
            }
            if ("hr".equals(stream)) {
                return List.of(hr);
            }
            return List.of();
        });
        given(s3Service.downloadBytes("imu-key")).willReturn(imuJson.getBytes(StandardCharsets.UTF_8));
        given(s3Service.downloadBytes("hr-key")).willReturn(hrJson.getBytes(StandardCharsets.UTF_8));

        // when
        new SensorRangeExportAssembler(batchRepository, s3Service, new ObjectMapper())
                .build(export(), workDir);

        // then
        List<String> gyroRows = Files.readAllLines(workDir.resolve("imu_watch_gyro.jsonl"));
        List<String> heartRows = Files.readAllLines(workDir.resolve("hr.jsonl"));
        assertThat(gyroRows).hasSize(2);
        assertThat(heartRows).hasSize(1);
        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(gyroRows.get(0)).get("ts_ms").asLong()).isEqualTo(1001L);
        assertThat(mapper.readTree(gyroRows.get(0)).get("x").asInt()).isEqualTo(4);
        assertThat(mapper.readTree(gyroRows.get(1)).get("ts_ms").asLong()).isEqualTo(1002L);
        assertThat(mapper.readTree(heartRows.get(0)).get("bpm").asInt()).isEqualTo(61);
        assertThat(Files.exists(workDir.resolve("imu_watch_acc.jsonl"))).isFalse();

        JsonNode manifest = mapper.readTree(Files.readString(workDir.resolve("manifest.json")));
        assertThat(manifest.get("total_sample_count").asLong()).isEqualTo(3L);
        assertThat(manifest.get("files")).hasSize(2);
        for (JsonNode file : manifest.get("files")) {
            Path path = workDir.resolve(file.get("path").asText());
            assertThat(file.get("bytes").asLong()).isEqualTo(Files.size(path));
            assertThat(file.get("sha256").asText()).isEqualTo(sha256(Files.readAllBytes(path)));
        }
    }

    @Test
    @DisplayName("구간에 샘플이 없으면 파일 목록이 빈 manifest만 생성한다")
    void 구간에_샘플이_없으면_빈_manifest만_생성한다() throws Exception {
        // given
        SensorRangeExportAssembler assembler = new SensorRangeExportAssembler(
                batchRepository, s3Service, new ObjectMapper());

        // when
        assembler.build(export(), workDir);

        // then
        JsonNode manifest = new ObjectMapper().readTree(Files.readString(workDir.resolve("manifest.json")));
        assertThat(manifest.get("total_sample_count").asLong()).isZero();
        assertThat(manifest.get("files")).isEmpty();
        assertThat(Files.list(workDir).count()).isEqualTo(1L);
    }

    private SensorRangeExport export() {
        return SensorRangeExport.builder()
                .exportId("sexp-test")
                .memberId(7L)
                .startAtMs(1001L)
                .endAtMs(1003L)
                .status(SensorRangeExportStatus.QUEUED)
                .requestedAtMs(1000L)
                .build();
    }

    private SensorBatch batch(long id, String stream, String batchId, String key, String json) throws Exception {
        SensorBatch batch = mock(SensorBatch.class);
        given(batch.getId()).willReturn(id);
        given(batch.getStream()).willReturn(stream);
        given(batch.getBatchId()).willReturn(batchId);
        given(batch.getS3Key()).willReturn(key);
        given(batch.getPayloadSha256()).willReturn(sha256(json.getBytes(StandardCharsets.UTF_8)));
        given(batch.getMeasuredAtStartMs()).willReturn(1000L);
        return batch;
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
