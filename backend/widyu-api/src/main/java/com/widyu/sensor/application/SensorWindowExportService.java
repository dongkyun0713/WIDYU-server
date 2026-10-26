package com.widyu.sensor.application;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.device.DeviceHeartbeat;
import com.widyu.device.repository.DeviceHeartbeatRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.location.raw.LocationFix;
import com.widyu.location.raw.repository.LocationFixRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.sensor.SensorBatch;
import com.widyu.sensor.application.SensorWindowCsv.Window;
import com.widyu.sensor.dto.request.HeartRateBatchRequest;
import com.widyu.sensor.dto.request.SensorBatchRequest;
import com.widyu.sensor.dto.response.SensorWindowExportFile;
import com.widyu.sensor.repository.SensorBatchRepository;
import jakarta.annotation.PreDestroy;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 회원·시간대로 위치 원본과 심박·움직임 자료를 CSV zip으로 만든다(LLD-0108).
 *
 * <p>트랜잭션을 두지 않는다. 저장소 조회가 각자 읽기 트랜잭션에서 끝나므로 S3를 받는 동안 DB 커넥션을 붙잡지 않는다.
 * 좌표·심박 값은 로그에 남기지 않고 회원 ID와 행 수만 남긴다(LLD-0029).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensorWindowExportService {

    private static final long MAX_WINDOW_MS = Duration.ofMinutes(90).toMillis();
    private static final int MAX_PAD_MINUTES = 60;
    private static final int DOWNLOAD_THREADS = 32;
    private static final int DOWNLOAD_CHUNK = 256;
    private static final String HR = "hr";
    private static final String IMU_PREFIX = "imu_";
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm").withZone(SensorWindowCsv.KST);

    private final MemberRepository memberRepository;
    private final LocationFixRepository locationFixRepository;
    private final DeviceHeartbeatRepository deviceHeartbeatRepository;
    private final SensorBatchRepository sensorBatchRepository;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;

    // ponytail: 이 기능 전용 고정 풀. 1시간 구간은 묶음이 약 4,800개(IMU 1초·심박 3초 묶음)라 하나씩 받으면
    // Nginx 60초를 넘는다. 동시 내보내기가 잦아지면 AsyncConfig의 이름 있는 실행기로 옮긴다.
    private final ExecutorService downloads = Executors.newFixedThreadPool(DOWNLOAD_THREADS,
            Thread.ofPlatform().daemon().name("sensor-export-", 0).factory());

    @PreDestroy
    void close() {
        downloads.shutdownNow();
    }

    public SensorWindowExportFile export(Long memberId, LocalDateTime from, LocalDateTime to, int padMinutes) {
        Window window = window(from, to, padMinutes);
        if (!memberRepository.existsById(memberId)) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
        }
        List<LocationFix> fixes = locationFixRepository.findByMemberIdAndTsMsBetweenOrderByTsMsAscSeqAsc(
                memberId, window.startMs(), window.endMs());
        List<DeviceHeartbeat> heartbeats = deviceHeartbeatRepository.findByMemberIdAndTsMsBetweenOrderByTsMsAsc(
                memberId, window.startMs(), window.endMs());
        List<SensorBatch> batches = sensorBatchRepository.findMemberWindowBatches(
                memberId, window.startMs(), window.endMs());

        Map<String, Long> counts = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(zipBytes);
        // writer를 닫으면 zip도 닫혀 마지막 디렉터리 정보까지 쓴다. 항목 경계마다 flush한 뒤 항목을 닫는다.
        try (Writer out = new BufferedWriter(new OutputStreamWriter(zip, UTF_8))) {
            counts.put("location.csv", writeEntry(zip, out, "location.csv", SensorWindowCsv.LOCATION_HEADER,
                    fixes.stream().map(fix -> SensorWindowCsv.location(fix, window)).toList()));
            counts.put("sensor_batches.csv", writeEntry(zip, out, "sensor_batches.csv",
                    SensorWindowCsv.BATCH_HEADER,
                    batches.stream().map(batch -> SensorWindowCsv.batch(batch, window)).toList()));
            counts.put("device_heartbeat.csv", writeEntry(zip, out, "device_heartbeat.csv",
                    SensorWindowCsv.HEARTBEAT_HEADER,
                    heartbeats.stream().map(heartbeat -> SensorWindowCsv.heartbeat(heartbeat, window)).toList()));
            counts.put("hr.csv", writeExpanded(zip, out, "hr.csv", SensorWindowCsv.HR_HEADER,
                    batches.stream().filter(batch -> HR.equals(batch.getStream())).toList(), window, skipped));
            counts.put("imu.csv", writeExpanded(zip, out, "imu.csv", SensorWindowCsv.IMU_HEADER,
                    batches.stream().filter(batch -> batch.getStream().startsWith(IMU_PREFIX)).toList(),
                    window, skipped));
            writeEntry(zip, out, "summary.csv", SensorWindowCsv.line("key", "value"),
                    summary(memberId, padMinutes, window, counts, skipped));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        log.info("센서 시간대 내보내기: memberId={}, locations={}, batches={}, heartbeats={}, hr={}, imu={}, skipped={}",
                memberId, fixes.size(), batches.size(), heartbeats.size(), counts.get("hr.csv"),
                counts.get("imu.csv"), skipped.size());
        return SensorWindowExportFile.of(fileName(memberId, window), zipBytes.toByteArray());
    }

    private Window window(LocalDateTime from, LocalDateTime to, int padMinutes) {
        if (!from.isBefore(to) || padMinutes < 0 || padMinutes > MAX_PAD_MINUTES) {
            throw new BusinessException(ErrorCode.SENSOR_EXPORT_RANGE_INVALID);
        }
        long fromMs = from.atZone(SensorWindowCsv.KST).toInstant().toEpochMilli();
        long toMs = to.atZone(SensorWindowCsv.KST).toInstant().toEpochMilli();
        long padMs = Duration.ofMinutes(padMinutes).toMillis();
        Window window = new Window(fromMs, toMs, fromMs - padMs, toMs + padMs);
        if (window.endMs() - window.startMs() > MAX_WINDOW_MS) {
            throw new BusinessException(ErrorCode.SENSOR_EXPORT_RANGE_INVALID);
        }
        return window;
    }

    private long writeEntry(ZipOutputStream zip, Writer out, String name, String header, List<String> rows)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        out.write(header);
        for (String row : rows) {
            out.write(row);
        }
        out.flush();
        zip.closeEntry();
        return rows.size();
    }

    /**
     * S3 원문을 순서대로 펼친다. 묶음을 조각으로 나눠 조각 안에서만 병렬로 받아 메모리를 묶어 둔다.
     * S3 호출 실패는 요청 전체를 실패시키고, 원문 손상은 그 묶음만 건너뛰어 요약에 남긴다.
     */
    private long writeExpanded(ZipOutputStream zip, Writer out, String name, String header,
                               List<SensorBatch> batches, Window window, List<String> skipped) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        out.write(header);
        long rows = 0;
        for (int start = 0; start < batches.size(); start += DOWNLOAD_CHUNK) {
            List<SensorBatch> chunk = batches.subList(start, Math.min(start + DOWNLOAD_CHUNK, batches.size()));
            List<CompletableFuture<byte[]>> payloads = chunk.stream()
                    .map(batch -> CompletableFuture.supplyAsync(
                            () -> s3Service.downloadBytes(batch.getS3Key()), downloads))
                    .toList();
            for (int index = 0; index < chunk.size(); index++) {
                SensorBatch batch = chunk.get(index);
                List<String> expanded = expand(batch, join(payloads.get(index)), window, skipped);
                for (String row : expanded) {
                    out.write(row);
                }
                rows += expanded.size();
            }
        }
        out.flush();
        zip.closeEntry();
        return rows;
    }

    private List<String> expand(SensorBatch batch, byte[] payload, Window window, List<String> skipped) {
        try {
            if (HR.equals(batch.getStream())) {
                return SensorWindowCsv.heartRows(batch,
                        objectMapper.readValue(payload, HeartRateBatchRequest.class), window);
            }
            return SensorWindowCsv.imuRows(batch, objectMapper.readValue(payload, SensorBatchRequest.class), window);
        } catch (IOException e) {
            skipped.add(batch.getBatchId() + " 원문 해석 불가");
        } catch (IllegalArgumentException e) {
            skipped.add(batch.getBatchId() + " " + e.getMessage());
        }
        return List.of();
    }

    private byte[] join(CompletableFuture<byte[]> payload) {
        try {
            return payload.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    private List<String> summary(Long memberId, int padMinutes, Window window, Map<String, Long> counts,
                                 List<String> skipped) {
        List<String> rows = new ArrayList<>();
        rows.add(SensorWindowCsv.line("member_id", memberId));
        rows.add(SensorWindowCsv.line("from_kst", SensorWindowCsv.kst(window.fromMs())));
        rows.add(SensorWindowCsv.line("to_kst", SensorWindowCsv.kst(window.toMs())));
        rows.add(SensorWindowCsv.line("pad_minutes", padMinutes));
        rows.add(SensorWindowCsv.line("window_start_kst", SensorWindowCsv.kst(window.startMs())));
        rows.add(SensorWindowCsv.line("window_end_kst", SensorWindowCsv.kst(window.endMs())));
        rows.add(SensorWindowCsv.line("generated_at_kst", SensorWindowCsv.kst(Instant.now().toEpochMilli())));
        counts.forEach((file, count) -> rows.add(SensorWindowCsv.line("rows:" + file, count)));
        skipped.forEach(reason -> rows.add(SensorWindowCsv.line("skipped_batch", reason)));
        return rows;
    }

    private String fileName(Long memberId, Window window) {
        return "sensor_member%d_%s-%s.zip".formatted(memberId,
                FILE_TIME.format(Instant.ofEpochMilli(window.startMs())),
                FILE_TIME.format(Instant.ofEpochMilli(window.endMs())));
    }
}
