package com.widyu.sensor.application;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.device.repository.DeviceHeartbeatRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.location.raw.LocationFix;
import com.widyu.location.raw.repository.LocationFixRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.sensor.SensorBatch;
import com.widyu.sensor.dto.response.SensorWindowExportFile;
import com.widyu.sensor.repository.SensorBatchRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SensorWindowExportServiceTest {

    // 2026-10-30 09:10 KST = 00:10Z
    private static final long FROM_MS = 1_793_319_000_000L;
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 30, 9, 10);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 10, 30, 9, 28);

    @Mock private MemberRepository memberRepository;
    @Mock private LocationFixRepository locationFixRepository;
    @Mock private DeviceHeartbeatRepository deviceHeartbeatRepository;
    @Mock private SensorBatchRepository sensorBatchRepository;
    @Mock private S3Service s3Service;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private SensorWindowExportService service;

    @Test
    @DisplayName("시간대와 여유로 구간을 만들어 CSV 여섯 개를 zip으로 내려주고 손상 묶음은 요약에 남긴다")
    void 시간대_자료를_CSV_zip으로_내보낸다() throws Exception {
        // given
        long startMs = FROM_MS - 600_000;
        long endMs = FROM_MS + 18 * 60_000 + 600_000;
        given(memberRepository.existsById(28L)).willReturn(true);
        given(locationFixRepository.findByMemberIdAndTsMsBetweenOrderByTsMsAscSeqAsc(28L, startMs, endMs))
                .willReturn(List.of(LocationFix.builder().memberId(28L).deviceId("phone-1").sessionId("s")
                        .seq(1L).tsMs(FROM_MS - 60_000).lat(37.5).lon(127.0).accuracyM(8.0).speedMps(1.2)
                        .isMock(false).reason("move").build()));
        given(deviceHeartbeatRepository.findByMemberIdAndTsMsBetweenOrderByTsMsAsc(28L, startMs, endMs))
                .willReturn(List.of());
        SensorBatch heart = batch("h1", "hr", "sensor/h1.json");
        SensorBatch brokenImu = batch("i1", "imu_watch", "sensor/i1.json");
        given(sensorBatchRepository.findMemberWindowBatches(28L, startMs, endMs))
                .willReturn(List.of(heart, brokenImu));
        given(s3Service.downloadBytes("sensor/h1.json")).willReturn("""
                {"batch_id":"h1","stream":"hr","samples":[{"bpm":88,"ts_ms":%d,"accuracy":"HIGH"}]}
                """.formatted(FROM_MS + 60_000).getBytes(UTF_8));
        given(s3Service.downloadBytes("sensor/i1.json")).willReturn("{not json".getBytes(UTF_8));

        // when
        SensorWindowExportFile file = service.export(28L, FROM, TO, 10);

        // then
        Map<String, String> entries = unzip(file.bytes());
        assertThat(file.fileName()).isEqualTo("sensor_member28_20261030T0900-20261030T0938.zip");
        assertThat(entries).containsOnlyKeys("location.csv", "sensor_batches.csv", "device_heartbeat.csv",
                "hr.csv", "imu.csv", "summary.csv");
        assertThat(entries.get("location.csv")).contains("before,2026-10-30 09:09:00.000," + (FROM_MS - 60_000)
                + ",phone-1,s,1,37.5,127.0,8.0,1.2");
        assertThat(entries.get("hr.csv")).contains("during,2026-10-30 09:11:00.000").contains(",h1,88,HIGH");
        assertThat(entries.get("imu.csv").lines()).hasSize(1);
        assertThat(entries.get("summary.csv"))
                .contains("rows:location.csv,1")
                .contains("rows:hr.csv,1")
                .contains("skipped_batch,i1 원문 해석 불가");
    }

    @Test
    @DisplayName("S3 원문을 받지 못하면 빠진 자료를 감추지 않고 내보내기 전체가 실패한다")
    void S3_다운로드가_실패하면_내보내기_전체가_실패한다() {
        // given
        long startMs = FROM_MS - 600_000;
        long endMs = FROM_MS + 18 * 60_000 + 600_000;
        given(memberRepository.existsById(28L)).willReturn(true);
        given(locationFixRepository.findByMemberIdAndTsMsBetweenOrderByTsMsAscSeqAsc(28L, startMs, endMs))
                .willReturn(List.of());
        given(deviceHeartbeatRepository.findByMemberIdAndTsMsBetweenOrderByTsMsAsc(28L, startMs, endMs))
                .willReturn(List.of());
        SensorBatch heart = mock(SensorBatch.class);
        given(heart.getStream()).willReturn("hr");
        given(heart.getS3Key()).willReturn("sensor/h1.json");
        given(heart.getMeasuredAtStartMs()).willReturn(FROM_MS);
        given(sensorBatchRepository.findMemberWindowBatches(28L, startMs, endMs)).willReturn(List.of(heart));
        BusinessException failure = new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        given(s3Service.downloadBytes("sensor/h1.json")).willThrow(failure);

        // when & then
        assertThatThrownBy(() -> service.export(28L, FROM, TO, 10)).isSameAs(failure);
    }

    @Test
    @DisplayName("시작 시각이 끝 시각보다 늦거나 같으면 시간대 오류가 발생한다")
    void 시작이_끝보다_늦으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> service.export(28L, TO, FROM, 10))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SENSOR_EXPORT_RANGE_INVALID);
        then(locationFixRepository).should(never())
                .findByMemberIdAndTsMsBetweenOrderByTsMsAscSeqAsc(anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("앞뒤 여유를 포함한 구간이 90분을 넘으면 시간대 오류가 발생한다")
    void 구간이_90분을_넘으면_예외가_발생한다() {
        // when & then: 71분 + 앞뒤 10분 = 91분
        assertThatThrownBy(() -> service.export(28L, FROM, FROM.plusMinutes(71), 10))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SENSOR_EXPORT_RANGE_INVALID);
    }

    @Test
    @DisplayName("없는 회원이면 회원 없음 예외가 발생한다")
    void 없는_회원이면_예외가_발생한다() {
        // given
        given(memberRepository.existsById(99L)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> service.export(99L, FROM, TO, 10))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
    }

    private SensorBatch batch(String batchId, String stream, String s3Key) {
        SensorBatch batch = mock(SensorBatch.class);
        given(batch.getBatchId()).willReturn(batchId);
        given(batch.getStream()).willReturn(stream);
        given(batch.getS3Key()).willReturn(s3Key);
        given(batch.getMeasuredAtStartMs()).willReturn(FROM_MS);
        return batch;
    }

    private Map<String, String> unzip(byte[] bytes) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), UTF_8));
            }
        }
        return entries;
    }
}
