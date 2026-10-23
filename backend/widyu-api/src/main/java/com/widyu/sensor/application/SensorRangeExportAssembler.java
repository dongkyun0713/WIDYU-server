package com.widyu.sensor.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.sensor.SensorBatch;
import com.widyu.sensor.SensorRangeExport;
import com.widyu.sensor.dto.request.HeartRateBatchRequest;
import com.widyu.sensor.dto.request.SensorBatchRequest;
import com.widyu.sensor.repository.SensorBatchRepository;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** S3의 원본 배치를 읽고 측정 시각이 요청 구간에 있는 샘플만 JSONL로 푼다. */
@Component
public class SensorRangeExportAssembler {

    private static final int PAGE_SIZE = 250;
    private static final String IMU_WATCH = "imu_watch";
    private static final String IMU_PHONE = "imu_phone";
    private static final String HR = "hr";

    private final SensorBatchRepository batchRepository;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;

    public SensorRangeExportAssembler(SensorBatchRepository batchRepository,
                                      S3Service s3Service, ObjectMapper objectMapper) {
        this.batchRepository = batchRepository;
        this.s3Service = s3Service;
        this.objectMapper = objectMapper;
    }

    public void build(SensorRangeExport export, Path workDir) throws IOException {
        Map<String, Output> outputs = new LinkedHashMap<>();
        try {
            outputs.put("imu_watch_acc.jsonl", new Output(workDir.resolve("imu_watch_acc.jsonl")));
            outputs.put("imu_watch_gyro.jsonl", new Output(workDir.resolve("imu_watch_gyro.jsonl")));
            outputs.put("imu_phone_acc.jsonl", new Output(workDir.resolve("imu_phone_acc.jsonl")));
            outputs.put("imu_phone_gyro.jsonl", new Output(workDir.resolve("imu_phone_gyro.jsonl")));
            outputs.put("hr.jsonl", new Output(workDir.resolve("hr.jsonl")));

            writeStream(export, IMU_WATCH, outputs);
            writeStream(export, IMU_PHONE, outputs);
            writeStream(export, HR, outputs);
        } finally {
            for (Output output : outputs.values()) {
                output.close();
            }
        }
        writeManifest(export, workDir, outputs);
    }

    private void writeStream(SensorRangeExport export, String stream, Map<String, Output> outputs)
            throws IOException {
        long lastStartMs = -1;
        long lastId = 0;
        List<SensorBatch> page;
        do {
            page = batchRepository.findRangePage(
                    export.getMemberId(), stream, export.getStartAtMs(), export.getEndAtMs(),
                    lastStartMs, lastId, PageRequest.of(0, PAGE_SIZE));
            for (SensorBatch batch : page) {
                byte[] original = s3Service.downloadBytes(batch.getS3Key());
                if (!sha256(original).equals(batch.getPayloadSha256())) {
                    throw new IOException("센서 원본 해시 불일치");
                }
                if (HR.equals(stream)) {
                    writeHeart(export, batch, original, outputs.get("hr.jsonl"));
                } else {
                    writeImu(export, batch, original, outputs.get(stream + "_acc.jsonl"),
                            outputs.get(stream + "_gyro.jsonl"));
                }
                lastStartMs = batch.getMeasuredAtStartMs();
                lastId = batch.getId();
            }
        } while (page.size() == PAGE_SIZE);
    }

    private void writeHeart(SensorRangeExport export, SensorBatch batch, byte[] original, Output output)
            throws IOException {
        HeartRateBatchRequest request = objectMapper.readValue(original, HeartRateBatchRequest.class);
        if (!HR.equals(batch.getStream()) || !HR.equals(request.stream())
                || !batch.getBatchId().equals(request.batchId())
                || request.samples() == null) {
            throw new IOException("심박 원본과 인덱스가 다릅니다.");
        }
        for (HeartRateBatchRequest.Sample sample : request.samples()) {
            if (sample.tsMs() == null || sample.bpm() == null || sample.accuracy() == null) {
                throw new IOException("심박 샘플이 손상됐습니다.");
            }
            if (!within(export, sample.tsMs())) {
                continue;
            }
            ObjectNode row = objectMapper.createObjectNode();
            row.put("ts_ms", sample.tsMs());
            row.put("device_id", request.deviceId());
            row.put("batch_id", request.batchId());
            row.put("bpm", sample.bpm());
            row.put("accuracy", sample.accuracy());
            output.write(objectMapper.writeValueAsString(row));
        }
    }

    private void writeImu(SensorRangeExport export, SensorBatch batch, byte[] original,
                          Output accOutput, Output gyroOutput) throws IOException {
        SensorBatchRequest request = objectMapper.readValue(original, SensorBatchRequest.class);
        if (!batch.getStream().equals(request.stream()) || !batch.getBatchId().equals(request.batchId())
                || request.clock() == null) {
            throw new IOException("IMU 원본과 인덱스가 다릅니다.");
        }
        writeAxis(export, request, request.acc(), true, accOutput);
        writeAxis(export, request, request.gyro(), false, gyroOutput);
    }

    private void writeAxis(SensorRangeExport export, SensorBatchRequest request,
                           SensorBatchRequest.Axis axis, boolean acceleration, Output output) throws IOException {
        if (axis == null) {
            return;
        }
        List<List<Integer>> values;
        if (acceleration) {
            values = axis.mg();
        } else {
            values = axis.mrads();
        }
        if (axis.n() == null || axis.n() < 1 || values == null || values.size() != axis.n()
                || axis.dtNs() == null || axis.dtNs().size() != axis.n() - 1
                || axis.t0ElapsedNs() == null) {
            throw new IOException("IMU 축이 손상됐습니다.");
        }
        try {
            long elapsedNs = Long.parseLong(axis.t0ElapsedNs());
            long anchorElapsedNs = Long.parseLong(request.clock().anchorElapsedNs());
            long anchorEpochMs = request.clock().anchorEpochMs();
            for (int index = 0; index < axis.n(); index++) {
                if (index > 0) {
                    elapsedNs = Math.addExact(elapsedNs, axis.dtNs().get(index - 1));
                }
                long offsetNs = Math.subtractExact(elapsedNs, anchorElapsedNs);
                long tsMs = Math.addExact(anchorEpochMs, Math.floorDiv(offsetNs, 1_000_000));
                if (!within(export, tsMs)) {
                    continue;
                }
                List<Integer> sample = values.get(index);
                if (sample == null || sample.size() != 3 || sample.contains(null)) {
                    throw new IOException("IMU 샘플이 손상됐습니다.");
                }
                ObjectNode row = objectMapper.createObjectNode();
                row.put("ts_ms", tsMs);
                row.put("device_id", request.deviceId());
                row.put("batch_id", request.batchId());
                row.put("x", sample.get(0));
                row.put("y", sample.get(1));
                row.put("z", sample.get(2));
                output.write(objectMapper.writeValueAsString(row));
            }
        } catch (ArithmeticException | NumberFormatException | NullPointerException e) {
            throw new IOException("IMU 시각을 읽을 수 없습니다.", e);
        }
    }

    private boolean within(SensorRangeExport export, long tsMs) {
        return tsMs >= export.getStartAtMs() && tsMs < export.getEndAtMs();
    }

    private void writeManifest(SensorRangeExport export, Path workDir, Map<String, Output> outputs)
            throws IOException {
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("format_version", "SENSOR_RANGE_EXPORT v1");
        manifest.put("member_ref", "member-" + export.getMemberId());
        manifest.put("start_at_ms", export.getStartAtMs());
        manifest.put("end_at_ms", export.getEndAtMs());
        manifest.put("time_boundary", "[start,end)");
        manifest.put("exported_at_ms", System.currentTimeMillis());
        ArrayNode files = manifest.putArray("files");
        long totalSamples = 0;
        for (Map.Entry<String, Output> entry : outputs.entrySet()) {
            Output output = entry.getValue();
            if (output.count == 0) {
                Files.deleteIfExists(output.path);
                continue;
            }
            ObjectNode file = files.addObject();
            file.put("path", entry.getKey());
            file.put("sample_count", output.count);
            file.put("bytes", Files.size(output.path));
            file.put("sha256", sha256(output.path));
            if (entry.getKey().endsWith("_acc.jsonl")) {
                file.put("unit", "mg");
            } else if (entry.getKey().endsWith("_gyro.jsonl")) {
                file.put("unit", "mrad/s");
            } else {
                file.put("unit", "bpm");
            }
            totalSamples += output.count;
        }
        manifest.put("total_sample_count", totalSamples);
        Files.writeString(workDir.resolve("manifest.json"), objectMapper.writeValueAsString(manifest));
    }

    private String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private static final class Output implements AutoCloseable {
        private final Path path;
        private final BufferedWriter writer;
        private long count;

        private Output(Path path) throws IOException {
            this.path = path;
            this.writer = Files.newBufferedWriter(path);
        }

        private void write(String row) throws IOException {
            writer.write(row);
            writer.newLine();
            count++;
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }
}
