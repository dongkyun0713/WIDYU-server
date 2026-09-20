package com.widyu.run.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.widyu.device.DeviceHeartbeat;
import com.widyu.device.repository.DeviceHeartbeatRepository;
import com.widyu.decision.DecisionRecord;
import com.widyu.decision.repository.DecisionRecordRepository;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.global.properties.SensorProperties;
import com.widyu.location.raw.LocationFix;
import com.widyu.location.raw.repository.LocationFixRepository;
import com.widyu.run.CollectionRun;
import com.widyu.run.RunDeviceAssignment;
import com.widyu.run.RunMarker;
import com.widyu.run.repository.RunDeviceAssignmentRepository;
import com.widyu.run.repository.RunMarkerRepository;
import com.widyu.sensor.ClockMapping;
import com.widyu.sensor.SensorBatch;
import com.widyu.sensor.repository.ClockMappingRepository;
import com.widyu.sensor.repository.SensorBatchRepository;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회차 자료를 내보내기 레이아웃으로 재조립한다(LLD-0050 5.3~5.7, 형식서 §1~§7).
 *
 * <p>서버 저장 레이아웃과 내보내기 레이아웃은 다르다. IMU·심박은 S3 원문 + 인덱스 행이고
 * 위치·하트비트는 RDB 행의 원문 텍스트다. 여기서 회차·스트림·기기 단위 JSONL로 묶고
 * 레코드마다 {@code _server{}} 봉투를 붙인다.
 *
 * <p>집계(줄 수·샘플 수·시각 범위·해시)는 <b>쓴 파일을 다시 읽어</b> 센다. 값을 옮겨 적지 않는다.
 */
@Component
@RequiredArgsConstructor
public class RunExportAssembler {

    private static final String FORMAT_VERSION = "EXPORT_FORMAT v0.2";
    private static final String STREAM_IMU_WATCH = "imu_watch";
    private static final String STREAM_IMU_PHONE = "imu_phone";
    private static final String STREAM_HR = "hr";
    private static final String STREAM_LOCATION = "location";
    private static final String STREAM_HEARTBEAT = "heartbeat";
    private static final String STREAM_MEASUREMENTS = "measurements";
    private static final String STREAM_INCIDENTS = "incidents";
    private static final String DECISIONS_FILE = "decisions.jsonl";
    private static final List<String> SENSOR_STREAMS =
            List.of(STREAM_IMU_WATCH, STREAM_IMU_PHONE, STREAM_HR);
    private static final String ROLE_WATCH = "watch";
    private static final String ROLE_PHONE = "phone";
    private static final String ROLE_EXTERNAL_ECG = "external_ecg";
    private static final String QUALITY_STATUS_OK = "OK";
    private static final String CLOCK_DOMAIN_UTC = "UTC_EPOCH_MS";
    private static final String DISPOSITION_UNRECOVERED = "UNRECOVERED";
    private static final String REASON_NOT_WORN = "NOT_WORN";
    private static final String REASON_DEVICE_OFF = "DEVICE_OFF";
    private static final String REASON_UNKNOWN = "UNKNOWN";
    private static final String RETENTION_UNDECIDED = "UNDECIDED";
    private static final String STREAMS_DIR = "streams";
    private static final long MILLIS_PER_SECOND = 1000L;

    private final SensorBatchRepository sensorBatchRepository;
    private final LocationFixRepository locationFixRepository;
    private final DeviceHeartbeatRepository deviceHeartbeatRepository;
    private final DecisionRecordRepository decisionRecordRepository;
    private final ClockMappingRepository clockMappingRepository;
    private final RunDeviceAssignmentRepository runDeviceAssignmentRepository;
    private final RunMarkerRepository runMarkerRepository;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;
    private final SensorProperties sensorProperties;

    /** 한 (스트림, 기기)의 집계 재료. 파일을 다시 읽어도 알 수 없는 값(도착 순서)을 여기 모은다. */
    private static final class StreamAccumulator {
        private final List<long[]> spans = new ArrayList<>();
        private int outOfOrderCount;
        private int resendCount;
    }

    private record StreamKey(String stream, String deviceId) {}

    @Transactional(readOnly = true)
    public void build(CollectionRun run, Path workDir) throws IOException {
        Files.createDirectories(workDir.resolve(STREAMS_DIR));

        List<RunDeviceAssignment> assignments =
                runDeviceAssignmentRepository.findByRun_IdOrderByIdAsc(run.getId());
        List<DeviceHeartbeat> heartbeats =
                deviceHeartbeatRepository.findByRunIdOrderByTsMsAsc(run.getRunId());
        Map<StreamKey, StreamAccumulator> accumulators = new LinkedHashMap<>();

        for (String stream : SENSOR_STREAMS) {
            writeSensorStream(run, stream, workDir, accumulators);
        }
        writeLocationStream(run, workDir, accumulators);
        writeHeartbeatStream(run, heartbeats, workDir, accumulators);
        writeDecisions(run, workDir);

        writeClockMappings(run, workDir);
        writeQuality(run, assignments, heartbeats, accumulators, workDir);
        writeRunJson(run, assignments, workDir);
        writeManifest(run, assignments, accumulators, workDir);
    }

    /** 판정 기록은 센서 스트림이 아니므로 manifest.files에는 넣지 않는다(LLD-0051 5.3). */
    private void writeDecisions(CollectionRun run, Path workDir) throws IOException {
        List<DecisionRecord> records = decisionRecordRepository.findByRunIdOrderByDecisionAtMsAsc(run.getRunId());
        if (records.isEmpty()) {
            return;
        }
        try (BufferedWriter writer = Files.newBufferedWriter(workDir.resolve(DECISIONS_FILE), StandardCharsets.UTF_8)) {
            for (DecisionRecord record : records) {
                writer.write(objectMapper.writeValueAsString(toDecisionRecord(record)));
                writer.write("\n");
            }
        }
    }

    private ObjectNode toDecisionRecord(DecisionRecord record) throws IOException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("decision_id", record.getDecisionId());
        node.put("member_id", record.getMemberId());
        putNullableString(node, "run_id", record.getRunId());
        node.set("stream_ids_used", objectMapper.readTree(record.getStreamIdsUsed()));
        node.put("decision_at_ms", record.getDecisionAtMs());
        node.put("decision_output", record.getDecisionOutput());
        node.put("decider_id", record.getDeciderId());
        node.put("decider_version", record.getDeciderVersion());
        node.put("input_cutoff_ms", record.getInputCutoffMs());
        node.put("feature_support_end_ms", record.getFeatureSupportEndMs());
        node.put("model_available_at_server_max_ms", record.getModelAvailableAtServerMaxMs());
        node.put("window_start_ms", record.getWindowStartMs());
        node.put("window_end_ms", record.getWindowEndMs());
        putNullableString(node, "alert_id", record.getAlertId());
        putNullableLong(node, "alert_at_ms", record.getAlertAtMs());
        putNullableString(node, "severity", record.getSeverity());
        putNullableString(node, "trigger_path", record.getTriggerPath());
        node.put("alert_delivered", record.getAlertDelivered());
        node.put("trigger_batch_id", record.getTriggerBatchId());
        return node;
    }

    // ── 스트림 파일 ────────────────────────────────────────────────

    private void writeSensorStream(
            CollectionRun run, String stream, Path workDir, Map<StreamKey, StreamAccumulator> accumulators)
            throws IOException {
        List<SensorBatch> batches =
                sensorBatchRepository.findByRunIdAndStreamOrderByMeasuredAtStartMsAscSeqAsc(
                        run.getRunId(), stream);
        if (batches.isEmpty()) {
            return;
        }
        Map<String, List<SensorBatch>> byDevice = new LinkedHashMap<>();
        for (SensorBatch batch : batches) {
            byDevice.computeIfAbsent(batch.getDeviceId(), key -> new ArrayList<>()).add(batch);
        }
        for (Map.Entry<String, List<SensorBatch>> entry : byDevice.entrySet()) {
            StreamAccumulator accumulator = new StreamAccumulator();
            try (BufferedWriter writer = newWriter(workDir, stream, entry.getKey())) {
                for (SensorBatch batch : entry.getValue()) {
                    writer.write(objectMapper.writeValueAsString(toSensorRecord(batch)));
                    writer.write("\n");
                    accumulator.spans.add(
                            new long[] {batch.getMeasuredAtStartMs(), batch.getMeasuredAtEndMs()});
                    if (Boolean.TRUE.equals(batch.getIsResend())) {
                        accumulator.resendCount++;
                    }
                }
            }
            accumulator.outOfOrderCount = countOutOfOrder(entry.getValue().stream()
                    .sorted(Comparator.comparing(SensorBatch::getSeq))
                    .map(batch -> new long[] {batch.getMeasuredAtStartMs(), batch.getMeasuredAtEndMs()})
                    .toList());
            accumulators.put(new StreamKey(stream, entry.getKey()), accumulator);
        }
    }

    private ObjectNode toSensorRecord(SensorBatch batch) throws IOException {
        ObjectNode record = readObject(new String(
                s3Service.downloadBytes(batch.getS3Key()), StandardCharsets.UTF_8));
        putIfAbsent(record, "member_id", batch.getMember().getId());
        putIfAbsent(record, "stream", batch.getStream());
        putIfAbsent(record, "boot_id", batch.getBootId());
        putIfAbsent(record, "clock_mapping_id", batch.getClockMappingId());

        ObjectNode server = newServer(
                batch.getMeasuredAtStartMs(), batch.getMeasuredAtEndMs(),
                batch.getPhoneReceivedAtMs(), batch.getServerReceivedAtMs(),
                batch.getAcceptedAtMs(), batch.getPersistedAtMs(),
                batch.getModelAvailableAtServerMs(), batch.getPayloadSha256(),
                batch.getQualityStatus());
        if (Boolean.TRUE.equals(batch.getIsResend())) {
            ObjectNode resend = objectMapper.createObjectNode();
            resend.put("is_resend", true);
            resend.put("original_batch_id", batch.getOriginalBatchId());
            putNullableLong(resend, "original_seq", batch.getOriginalSeq());
            resend.put("original_run_id", batch.getOriginalRunId());
            resend.put("original_session_id", batch.getOriginalSessionId());
            putNullableLong(resend, "resent_at_ms", batch.getResentAtMs());
            server.set("resend", resend);
        }
        boolean backfill = Boolean.TRUE.equals(batch.getGyroBackfill());
        server.put("is_backfill", backfill);
        // 보강일 때만 배열로 정규화한다. 아니면 null이다(형식서 §2.2 예시, 검사기 정상 표본).
        if (backfill) {
            server.set("backfill_for", backfillForArray(batch.getBackfillFor()));
        }
        putNullableString(server, "collection_mode", batch.getCollectionMode());
        record.set("_server", server);
        return record;
    }

    private void writeLocationStream(
            CollectionRun run, Path workDir, Map<StreamKey, StreamAccumulator> accumulators)
            throws IOException {
        List<LocationFix> fixes = locationFixRepository.findByRunIdOrderByTsMsAscSeqAsc(run.getRunId());
        if (fixes.isEmpty()) {
            return;
        }
        Map<String, List<LocationFix>> byDevice = new LinkedHashMap<>();
        for (LocationFix fix : fixes) {
            byDevice.computeIfAbsent(fix.getDeviceId(), key -> new ArrayList<>()).add(fix);
        }
        for (Map.Entry<String, List<LocationFix>> entry : byDevice.entrySet()) {
            StreamAccumulator accumulator = new StreamAccumulator();
            try (BufferedWriter writer = newWriter(workDir, STREAM_LOCATION, entry.getKey())) {
                for (LocationFix fix : entry.getValue()) {
                    ObjectNode record = readObject(fix.getPayload());
                    putIfAbsent(record, "member_id", fix.getMemberId());
                    putIfAbsent(record, "stream", STREAM_LOCATION);
                    // 앱이 불변 ID를 붙이지 않는 스트림이라 서버 행 ID로 만든다(ADR-0032 결정 1).
                    putIfAbsent(record, "batch_id", "loc-" + fix.getId());
                    record.set("_server", newServer(
                            fix.getTsMs(), fix.getTsMs(), null, fix.getServerReceivedAtMs(),
                            fix.getAcceptedAtMs(), fix.getPersistedAtMs(), fix.getPersistedAtMs(),
                            fix.getPayloadSha256(), QUALITY_STATUS_OK));
                    ((ObjectNode) record.get("_server")).put("is_backfill", false);
                    writer.write(objectMapper.writeValueAsString(record));
                    writer.write("\n");
                    accumulator.spans.add(new long[] {fix.getTsMs(), fix.getTsMs()});
                }
            }
            accumulator.outOfOrderCount = countOutOfOrder(entry.getValue().stream()
                    .sorted(Comparator.comparing(LocationFix::getSeq))
                    .map(fix -> new long[] {fix.getTsMs(), fix.getTsMs()})
                    .toList());
            accumulators.put(new StreamKey(STREAM_LOCATION, entry.getKey()), accumulator);
        }
    }

    private void writeHeartbeatStream(
            CollectionRun run,
            List<DeviceHeartbeat> heartbeats,
            Path workDir,
            Map<StreamKey, StreamAccumulator> accumulators) throws IOException {
        if (heartbeats.isEmpty()) {
            return;
        }
        Map<String, List<DeviceHeartbeat>> byDevice = new LinkedHashMap<>();
        for (DeviceHeartbeat heartbeat : heartbeats) {
            byDevice.computeIfAbsent(heartbeat.getDeviceId(), key -> new ArrayList<>()).add(heartbeat);
        }
        for (Map.Entry<String, List<DeviceHeartbeat>> entry : byDevice.entrySet()) {
            StreamAccumulator accumulator = new StreamAccumulator();
            // 앱이 seq를 싣지 않아 서버가 (device_id, session_id) 안에서 잰 시각 순으로 부여한다.
            Map<String, Long> sessionSequences = new HashMap<>();
            try (BufferedWriter writer = newWriter(workDir, STREAM_HEARTBEAT, entry.getKey())) {
                for (DeviceHeartbeat heartbeat : entry.getValue()) {
                    ObjectNode record = readObject(heartbeat.getPayload());
                    putIfAbsent(record, "member_id", heartbeat.getMemberId());
                    putIfAbsent(record, "stream", STREAM_HEARTBEAT);
                    putIfAbsent(record, "batch_id", "hb-" + heartbeat.getId());
                    long sequence = sessionSequences.merge(heartbeat.getSessionId(), 0L, (old, one) -> old + 1);
                    putIfAbsent(record, "seq", sequence);
                    record.set("_server", newServer(
                            heartbeat.getTsMs(), heartbeat.getTsMs(), null,
                            heartbeat.getServerReceivedAtMs(), heartbeat.getAcceptedAtMs(),
                            heartbeat.getPersistedAtMs(), heartbeat.getPersistedAtMs(),
                            heartbeat.getPayloadSha256(), QUALITY_STATUS_OK));
                    ((ObjectNode) record.get("_server")).put("is_backfill", false);
                    writer.write(objectMapper.writeValueAsString(record));
                    writer.write("\n");
                    accumulator.spans.add(new long[] {heartbeat.getTsMs(), heartbeat.getTsMs()});
                }
            }
            accumulators.put(new StreamKey(STREAM_HEARTBEAT, entry.getKey()), accumulator);
        }
    }

    /**
     * 서버 봉투. 관측하지 못한 값은 0이나 현재 시각으로 채우지 않고 null로 둔다(정책 1.2.3).
     * 세 계층의 가용 시각을 하나로 합치지 않는다(형식서 §2.2).
     */
    private ObjectNode newServer(
            Long measuredAtStartMs,
            Long measuredAtEndMs,
            Long phoneReceivedAtMs,
            Long serverReceivedAtMs,
            Long acceptedAtMs,
            Long persistedAtMs,
            Long modelAvailableAtServerMs,
            String payloadSha256,
            String qualityStatus
    ) {
        ObjectNode server = objectMapper.createObjectNode();
        putNullableLong(server, "measured_at_start_ms", measuredAtStartMs);
        putNullableLong(server, "measured_at_end_ms", measuredAtEndMs);
        putNullableLong(server, "phone_received_at_ms", phoneReceivedAtMs);
        putNullableLong(server, "server_received_at_ms", serverReceivedAtMs);
        putNullableLong(server, "accepted_at_ms", acceptedAtMs);
        putNullableLong(server, "persisted_at_ms", persistedAtMs);
        server.putNull("model_available_at_device_ms");
        server.putNull("model_available_at_phone_ms");
        putNullableLong(server, "model_available_at_server_ms", modelAvailableAtServerMs);
        putNullableString(server, "payload_sha256", payloadSha256);
        putNullableString(server, "quality_status", qualityStatus);
        server.putNull("resend");
        server.putNull("backfill_for");
        server.putNull("collection_mode");
        return server;
    }

    // ── clock_mappings.json ─────────────────────────────────────────

    private void writeClockMappings(CollectionRun run, Path workDir) throws IOException {
        Set<String> mappingIds = new LinkedHashSet<>();
        for (SensorBatch batch : sensorBatchRepository.findByRunIdOrderByMeasuredAtStartMsAsc(run.getRunId())) {
            mappingIds.add(batch.getClockMappingId());
        }
        ArrayNode mappings = objectMapper.createArrayNode();
        if (!mappingIds.isEmpty()) {
            SensorProperties.Export.Clock clock = sensorProperties.export().clock();
            for (ClockMapping mapping : clockMappingRepository.findByClockMappingIdIn(mappingIds)) {
                ObjectNode node = mappings.addObject();
                node.put("clock_mapping_id", mapping.getClockMappingId());
                node.put("device_id", mapping.getDeviceId());
                node.put("boot_id", mapping.getBootId());
                node.put("source_clock_domain", clock.sourceDomain());
                node.put("target_clock_domain", clock.targetDomain());
                ObjectNode anchor = node.putArray("anchors").addObject();
                // 나노초 절대값은 10진 문자열이다(형식서 §2.1).
                anchor.put("elapsed_ns", String.valueOf(mapping.getAnchorElapsedNs()));
                anchor.put("epoch_ms", mapping.getAnchorEpochMs());
                anchor.put("captured_at_ms", mapping.getFirstSeenAtMs());
                anchor.put("uncertainty_ms", mapping.getUncertaintyMs());
                node.put("valid_from_elapsed_ns", String.valueOf(mapping.getObservedMinElapsedNs()));
                node.put("valid_to_elapsed_ns", String.valueOf(mapping.getObservedMaxElapsedNs()));
                node.put("offset_ms", 0);
                node.putNull("drift_ppm");
                node.putNull("residual_ms");
                node.put("transform", clock.transform());
                node.put("evidence_method", clock.evidenceMethod());
                node.put("estimated_at_ms", mapping.getFirstSeenAtMs());
                node.putNull("superseded_by");
            }
        }
        writeJson(workDir.resolve("clock_mappings.json"), mappings);
    }

    // ── quality.json ───────────────────────────────────────────────

    private void writeQuality(
            CollectionRun run,
            List<RunDeviceAssignment> assignments,
            List<DeviceHeartbeat> heartbeats,
            Map<StreamKey, StreamAccumulator> accumulators,
            Path workDir) throws IOException {
        long endedAtMs = endedAtMs(run);
        ObjectNode quality = objectMapper.createObjectNode();
        quality.put("run_id", run.getRunId());
        quality.put("scheduled_duration_s", (endedAtMs - run.getStartedAtMs()) / MILLIS_PER_SECOND);

        ArrayNode perStream = quality.putArray("per_stream");
        ArrayNode missingIntervals = quality.putArray("missing_intervals");
        long unknownDurationMs = 0L;

        for (Map.Entry<StreamKey, StreamAccumulator> entry : accumulators.entrySet()) {
            StreamKey key = entry.getKey();
            StreamAccumulator accumulator = entry.getValue();
            long assignedMs = assignedDurationMs(assignments, key.deviceId(), endedAtMs);
            long expected = expectedSampleCount(key.stream(), assignedMs);
            long actual = actualSampleCount(workDir, key);

            List<ObjectNode> gaps = missingIntervals(
                    run, key, accumulator, assignments, heartbeats, endedAtMs);
            for (ObjectNode gap : gaps) {
                missingIntervals.add(gap);
                if (REASON_UNKNOWN.equals(gap.get("missing_reason").asText())) {
                    unknownDurationMs += gap.get("end_ms").asLong() - gap.get("start_ms").asLong();
                }
            }

            ObjectNode node = perStream.addObject();
            node.put("stream", key.stream());
            node.put("device_id", key.deviceId());
            node.put("expected_sample_count", expected);
            node.put("actual_sample_count", actual);
            // 자료가 없다고 분모를 줄이지 않는다. 줄이면 수집률이 늘 1.0이 된다(ADR-0032 결정 4).
            if (expected <= 0) {
                node.putNull("coverage");
            } else {
                node.put("coverage", Math.round(actual * 10000.0 / expected) / 10000.0);
            }
            node.put("gap_count", gaps.size());
            node.put("duplicate_count", 0);
            node.put("out_of_order_count", accumulator.outOfOrderCount);
            node.put("resend_count", accumulator.resendCount);
        }
        quality.put("unknown_duration_s", unknownDurationMs / MILLIS_PER_SECOND);
        writeJson(workDir.resolve("quality.json"), quality);
    }

    /** 배치 경계의 공백을 실제 값으로 신고한다. 여유를 붙여 넓게 적으면 검사기가 모순으로 잡는다(D12). */
    private List<ObjectNode> missingIntervals(
            CollectionRun run,
            StreamKey key,
            StreamAccumulator accumulator,
            List<RunDeviceAssignment> assignments,
            List<DeviceHeartbeat> heartbeats,
            long endedAtMs) {
        long threshold = sensorProperties.export().gapThresholdMs()
                .getOrDefault(key.stream(), Long.MAX_VALUE);
        List<long[]> spans = new ArrayList<>(accumulator.spans);
        spans.sort(Comparator.comparingLong(span -> span[0]));

        List<long[]> candidates = new ArrayList<>();
        long assignedFrom = assignedFromMs(assignments, key.deviceId(), run.getStartedAtMs());
        long assignedTo = assignedToMs(assignments, key.deviceId(), endedAtMs);
        if (!spans.isEmpty()) {
            candidates.add(new long[] {assignedFrom, spans.get(0)[0]});
            for (int i = 0; i + 1 < spans.size(); i++) {
                candidates.add(new long[] {spans.get(i)[1], spans.get(i + 1)[0]});
            }
            candidates.add(new long[] {spans.get(spans.size() - 1)[1], assignedTo});
        }

        List<ObjectNode> gaps = new ArrayList<>();
        for (long[] candidate : candidates) {
            if (candidate[1] - candidate[0] <= threshold) {
                continue;
            }
            ObjectNode gap = objectMapper.createObjectNode();
            gap.put("stream", key.stream());
            gap.put("device_id", key.deviceId());
            gap.put("run_id", run.getRunId());
            gap.put("start_ms", candidate[0]);
            gap.put("end_ms", candidate[1]);
            gap.put("clock_domain", CLOCK_DOMAIN_UTC);
            gap.put("missing_reason", missingReason(key.deviceId(), candidate, heartbeats));
            gap.put("disposition", DISPOSITION_UNRECOVERED);
            gaps.add(gap);
        }
        return gaps;
    }

    /** 사유를 모르면 UNKNOWN이라고 적는다. 빈칸으로 두지 않는다(형식서 §5). */
    private String missingReason(String deviceId, long[] interval, List<DeviceHeartbeat> heartbeats) {
        boolean notWorn = false;
        boolean deviceOff = false;
        for (DeviceHeartbeat heartbeat : heartbeats) {
            if (heartbeat.getTsMs() < interval[0] || heartbeat.getTsMs() >= interval[1]) {
                continue;
            }
            boolean watchDevice = deviceId.equals(heartbeat.getWatchDeviceId());
            boolean phoneDevice = deviceId.equals(heartbeat.getDeviceId());
            if (!watchDevice && !phoneDevice) {
                continue;
            }
            if (watchDevice && Boolean.FALSE.equals(heartbeat.getWatchOnBody())) {
                notWorn = true;
            }
            if (watchDevice && Boolean.FALSE.equals(heartbeat.getWatchConnected())) {
                deviceOff = true;
            }
            if (phoneDevice && Boolean.FALSE.equals(heartbeat.getSocketConnected())) {
                deviceOff = true;
            }
        }
        if (notWorn) {
            return REASON_NOT_WORN;
        }
        if (deviceOff) {
            return REASON_DEVICE_OFF;
        }
        return REASON_UNKNOWN;
    }

    // ── run.json ───────────────────────────────────────────────────

    private void writeRunJson(CollectionRun run, List<RunDeviceAssignment> assignments, Path workDir)
            throws IOException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("run_id", run.getRunId());
        putNullableString(node, "study_id", run.getStudyId());
        putNullableString(node, "participation_id", run.getParticipationId());
        node.put("started_at_ms", run.getStartedAtMs());
        putNullableLong(node, "ended_at_ms", run.getEndedAtMs());
        node.put("subject_ref", "member-" + run.getMember().getId());

        ArrayNode devices = node.putArray("devices");
        for (RunDeviceAssignment assignment : assignments) {
            ObjectNode device = devices.addObject();
            device.put("assignment_id", assignment.getAssignmentId());
            device.put("device_id", assignment.getDeviceId());
            device.put("role", assignment.getRole());
            putNullableString(device, "wear_site", assignment.getWearSite());
            device.put("assigned_at_ms", assignment.getAssignedAtMs());
            putNullableLong(device, "unassigned_at_ms", assignment.getUnassignedAtMs());
        }
        putNullableString(node, "protocol_ref", run.getProtocolRef());

        ArrayNode markers = node.putArray("markers");
        for (RunMarker marker : runMarkerRepository.findByRun_IdOrderByTsMsAsc(run.getId())) {
            ObjectNode markerNode = markers.addObject();
            markerNode.put("marker_id", marker.getMarkerId());
            markerNode.put("kind", marker.getKind());
            putNullableString(markerNode, "label", marker.getLabel());
            markerNode.put("source_elapsed_ns", String.valueOf(marker.getSourceElapsedNs()));
            markerNode.put("ts_ms", marker.getTsMs());
            markerNode.put("source", marker.getSource());
            markerNode.put("source_device_id", marker.getSourceDeviceId());
            markerNode.put("clock_mapping_id", marker.getClockMappingId());
        }

        ObjectNode quality = node.putObject("quality");
        putNullableString(quality, "notes", run.getQualityNotes());
        putNullableString(quality, "missing_reason", run.getMissingReason());
        putNullableString(node, "consent_version", run.getConsentVersion());

        // 보존 결정이 빠진 상태를 숨기지 않고 값으로 적는다(ADR-0032 결정 3).
        ObjectNode retention = node.putObject("retention");
        if (run.getDataPolicy() == null) {
            retention.put("data_policy", RETENTION_UNDECIDED);
            retention.putNull("identified_until");
            retention.putNull("pseudonymized_at");
            retention.putNull("research_until");
        } else {
            retention.put("data_policy", run.getDataPolicy());
            putNullableDate(retention, "identified_until", run.getIdentifiedUntil());
            putNullableDate(retention, "pseudonymized_at", run.getPseudonymizedAt());
            putNullableDate(retention, "research_until", run.getResearchUntil());
        }
        putNullableString(node, "collection_mode", run.getCollectionMode());
        putNullableString(node, "acceptance_receipt_id", run.getAcceptanceReceiptId());
        node.putArray("ecg_clock_offsets");
        writeJson(workDir.resolve("run.json"), node);
    }

    // ── manifest.json ──────────────────────────────────────────────

    private void writeManifest(
            CollectionRun run,
            List<RunDeviceAssignment> assignments,
            Map<StreamKey, StreamAccumulator> accumulators,
            Path workDir) throws IOException {
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("format_version", FORMAT_VERSION);
        manifest.put("run_id", run.getRunId());
        putNullableString(manifest, "study_id", run.getStudyId());
        putNullableString(manifest, "participation_id", run.getParticipationId());
        manifest.put("exported_at_ms", System.currentTimeMillis());
        manifest.put("server_build", sensorProperties.export().serverBuild());

        ArrayNode files = manifest.putArray("files");
        long totalRecords = 0L;
        long totalSamples = 0L;
        for (StreamKey key : accumulators.keySet()) {
            Path path = streamPath(workDir, key.stream(), key.deviceId());
            // 값을 옮겨 적지 않고 쓴 파일을 다시 읽어 센다(형식서 §7).
            List<JsonNode> lines = readLines(path);
            long recordCount = lines.size();
            long sampleCount = sampleCount(key.stream(), lines);
            ObjectNode file = files.addObject();
            file.put("path", STREAMS_DIR + "/" + fileName(key.stream(), key.deviceId()));
            file.put("stream", key.stream());
            if (STREAM_IMU_WATCH.equals(key.stream())) {
                file.put("source", ROLE_WATCH);
            }
            if (STREAM_IMU_PHONE.equals(key.stream())) {
                file.put("source", ROLE_PHONE);
            }
            file.put("device_id", key.deviceId());
            file.put("bytes", Files.size(path));
            file.put("sha256", sha256(Files.readAllBytes(path)));
            file.put("record_count", recordCount);
            file.put("sample_count", sampleCount);
            // 검사기가 레코드의 시작 시각 하나로 재계산한다. 끝 시각을 쓰면 어긋난다(검사기 I7).
            file.put("first_measured_at_ms", measuredBound(lines, "measured_at_start_ms", true));
            file.put("last_measured_at_ms", measuredBound(lines, "measured_at_start_ms", false));
            totalRecords += recordCount;
            totalSamples += sampleCount;
        }
        ObjectNode totals = manifest.putObject("totals");
        totals.put("record_count", totalRecords);
        totals.put("sample_count", totalSamples);
        manifest.set("streams_absent", streamsAbsent(assignments, accumulators));
        writeJson(workDir.resolve("manifest.json"), manifest);
    }

    /** 「없다」와 「없다고 적었다」는 다른 명제다(형식서 §1, 검사기 I10). 기대 쌍은 기기 역할이 만든다. */
    private ArrayNode streamsAbsent(
            List<RunDeviceAssignment> assignments, Map<StreamKey, StreamAccumulator> accumulators) {
        SensorProperties.Export export = sensorProperties.export();
        ArrayNode absent = objectMapper.createArrayNode();
        boolean hasEcg = false;
        for (RunDeviceAssignment assignment : assignments) {
            List<String> expected = expectedStreams(assignment.getRole());
            if (ROLE_EXTERNAL_ECG.equals(assignment.getRole())) {
                hasEcg = true;
            }
            for (String stream : expected) {
                if (accumulators.containsKey(new StreamKey(stream, assignment.getDeviceId()))) {
                    continue;
                }
                ObjectNode node = absent.addObject();
                node.put("stream", stream);
                node.put("device_id", assignment.getDeviceId());
                node.put("reason", export.absentReasonNoData());
            }
        }
        if (!hasEcg) {
            ObjectNode node = absent.addObject();
            node.put("stream", STREAM_MEASUREMENTS);
            node.put("reason", export.absentReasonRoleMissing());
        }
        ObjectNode incidents = absent.addObject();
        incidents.put("stream", STREAM_INCIDENTS);
        incidents.put("reason", export.absentReasonNotImplemented());
        return absent;
    }

    private List<String> expectedStreams(String role) {
        if (ROLE_WATCH.equals(role)) {
            return List.of(STREAM_IMU_WATCH, STREAM_HR);
        }
        if (ROLE_PHONE.equals(role)) {
            return List.of(STREAM_LOCATION, STREAM_HEARTBEAT, STREAM_IMU_PHONE);
        }
        if (ROLE_EXTERNAL_ECG.equals(role)) {
            return List.of(STREAM_MEASUREMENTS);
        }
        // 모르는 역할로는 기대 집합을 지어내지 않는다(검사기 I10).
        return List.of();
    }

    // ── 집계 도우미 ────────────────────────────────────────────────

    private long actualSampleCount(Path workDir, StreamKey key) throws IOException {
        return sampleCount(key.stream(), readLines(streamPath(workDir, key.stream(), key.deviceId())));
    }

    /** IMU = acc.n + gyro.n, 심박 = samples 길이, 그 밖 = 줄당 1(형식서 §7). */
    private long sampleCount(String stream, List<JsonNode> lines) {
        long total = 0L;
        for (JsonNode line : lines) {
            if (STREAM_IMU_WATCH.equals(stream) || STREAM_IMU_PHONE.equals(stream)) {
                total += axisCount(line.get("acc")) + axisCount(line.get("gyro"));
                continue;
            }
            if (STREAM_HR.equals(stream)) {
                JsonNode samples = line.get("samples");
                if (samples != null && samples.isArray()) {
                    total += samples.size();
                }
                continue;
            }
            total++;
        }
        return total;
    }

    private long axisCount(JsonNode axis) {
        if (axis == null || axis.isNull() || axis.get("n") == null) {
            return 0L;
        }
        return axis.get("n").asLong();
    }

    private long measuredBound(List<JsonNode> lines, String field, boolean minimum) {
        long bound = 0L;
        boolean first = true;
        for (JsonNode line : lines) {
            JsonNode value = line.get("_server").get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            if (first) {
                bound = value.asLong();
                first = false;
                continue;
            }
            if (minimum) {
                bound = Math.min(bound, value.asLong());
                continue;
            }
            bound = Math.max(bound, value.asLong());
        }
        return bound;
    }

    private int countOutOfOrder(List<long[]> arrivalOrder) {
        int count = 0;
        long previousEnd = Long.MIN_VALUE;
        for (long[] span : arrivalOrder) {
            if (span[0] < previousEnd) {
                count++;
            }
            previousEnd = Math.max(previousEnd, span[1]);
        }
        return count;
    }

    /** 배정 구간을 기대 간격으로 나눈다. 자료가 없다고 분모를 줄이지 않는다(ADR-0032 결정 4). */
    private long expectedSampleCount(String stream, long assignedMs) {
        Long periodMs = sensorProperties.export().expectedPeriodMs().get(stream);
        if (periodMs == null || periodMs <= 0) {
            return 0L;
        }
        return assignedMs / periodMs;
    }

    private long assignedDurationMs(
            List<RunDeviceAssignment> assignments, String deviceId, long endedAtMs) {
        long total = 0L;
        for (RunDeviceAssignment assignment : assignments) {
            if (!assignment.getDeviceId().equals(deviceId)) {
                continue;
            }
            total += unassignedAtMs(assignment, endedAtMs) - assignment.getAssignedAtMs();
        }
        return total;
    }

    private long assignedFromMs(
            List<RunDeviceAssignment> assignments, String deviceId, long startedAtMs) {
        long from = Long.MAX_VALUE;
        for (RunDeviceAssignment assignment : assignments) {
            if (assignment.getDeviceId().equals(deviceId)) {
                from = Math.min(from, assignment.getAssignedAtMs());
            }
        }
        if (from == Long.MAX_VALUE) {
            return startedAtMs;
        }
        return from;
    }

    private long assignedToMs(List<RunDeviceAssignment> assignments, String deviceId, long endedAtMs) {
        long to = Long.MIN_VALUE;
        for (RunDeviceAssignment assignment : assignments) {
            if (assignment.getDeviceId().equals(deviceId)) {
                to = Math.max(to, unassignedAtMs(assignment, endedAtMs));
            }
        }
        if (to == Long.MIN_VALUE) {
            return endedAtMs;
        }
        return to;
    }

    private long unassignedAtMs(RunDeviceAssignment assignment, long endedAtMs) {
        if (assignment.getUnassignedAtMs() == null) {
            return endedAtMs;
        }
        return assignment.getUnassignedAtMs();
    }

    private long endedAtMs(CollectionRun run) {
        if (run.getEndedAtMs() == null) {
            return run.getStartedAtMs();
        }
        return run.getEndedAtMs();
    }

    // ── 입출력 도우미 ──────────────────────────────────────────────

    private BufferedWriter newWriter(Path workDir, String stream, String deviceId) throws IOException {
        return Files.newBufferedWriter(
                streamPath(workDir, stream, deviceId), StandardCharsets.UTF_8);
    }

    private Path streamPath(Path workDir, String stream, String deviceId) {
        return workDir.resolve(STREAMS_DIR).resolve(fileName(stream, deviceId));
    }

    private String fileName(String stream, String deviceId) {
        return "%s_%s.jsonl".formatted(stream, deviceId);
    }

    private List<JsonNode> readLines(Path path) throws IOException {
        List<JsonNode> lines = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            lines.add(objectMapper.readTree(line));
        }
        return lines;
    }

    private ObjectNode readObject(String json) throws IOException {
        JsonNode node = objectMapper.readTree(json);
        if (!node.isObject()) {
            throw new IOException("레코드 원문이 JSON 객체가 아닙니다.");
        }
        return (ObjectNode) node;
    }

    private void writeJson(Path path, JsonNode node) throws IOException {
        Files.writeString(path, objectMapper.writeValueAsString(node), StandardCharsets.UTF_8);
    }

    private ArrayNode backfillForArray(String backfillFor) {
        ArrayNode array = objectMapper.createArrayNode();
        if (backfillFor == null || backfillFor.isBlank()) {
            return array;
        }
        for (String originalBatchId : backfillFor.split(",")) {
            array.add(originalBatchId.trim());
        }
        return array;
    }

    private void putIfAbsent(ObjectNode node, String field, String value) {
        if (node.has(field) || value == null) {
            return;
        }
        node.put(field, value);
    }

    private void putIfAbsent(ObjectNode node, String field, Long value) {
        if (node.has(field) || value == null) {
            return;
        }
        node.put(field, value);
    }

    private void putNullableLong(ObjectNode node, String field, Long value) {
        if (value == null) {
            node.putNull(field);
            return;
        }
        node.put(field, value);
    }

    private void putNullableString(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
            return;
        }
        node.put(field, value);
    }

    private void putNullableDate(ObjectNode node, String field, java.time.LocalDate value) {
        if (value == null) {
            node.putNull(field);
            return;
        }
        node.put(field, value.format(DateTimeFormatter.ISO_LOCAL_DATE));
    }

    private String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
