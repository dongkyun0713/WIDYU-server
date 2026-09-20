package com.widyu.decision.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.decision.DecisionRecord;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.global.properties.SensorProperties;
import com.widyu.heart.HeartRateEvent;
import com.widyu.heart.repository.HeartRateEventRepository;
import com.widyu.sensor.SensorBatch;
import com.widyu.sensor.repository.SensorBatchRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/** 충격 IMU 저장 뒤 판정 창을 조립하고 결과를 기록한다(LLD-0051 5.2). */
@Slf4j
@Service
@RequiredArgsConstructor
public class FallAssessmentService {

    private static final List<String> IMU_STREAMS = List.of("imu_watch", "imu_phone");
    private static final String ABSTAIN = "ABSTAIN_INSUFFICIENT_INPUT";
    private static final List<String> DECISIONS = List.of("ALERT", "NO_ALERT", ABSTAIN);
    private static final ZoneId HEART_RATE_ZONE = ZoneId.of("Asia/Seoul");

    private final SensorProperties sensorProperties;
    private final SensorBatchRepository sensorBatchRepository;
    private final HeartRateEventRepository heartRateEventRepository;
    private final FallAssessmentClient fallAssessmentClient;
    private final DecisionRecordPersistenceService decisionRecordPersistenceService;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;

    /** 외부 AI 호출은 배치 수신 트랜잭션에 참여하지 않는다. */
    public void assessAfterImpact(SensorBatch trigger) {
        if (!sensorProperties.fallAi().enabled()) {
            return;
        }
        if (trigger.getTriggerTsMs() == null) {
            return;
        }
        long inputCutoffMs = System.currentTimeMillis();
        long windowStartMs = trigger.getTriggerTsMs() - sensorProperties.fallAi().windowBeforeSec() * 1000L;
        long windowEndMs = trigger.getMeasuredAtEndMs();
        List<SensorBatch> inputs = sensorBatchRepository.findFallInputBatches(
                trigger.getMember().getId(), IMU_STREAMS, windowStartMs, windowEndMs, inputCutoffMs);
        if (inputs.stream().noneMatch(batch -> batch.getAccN() != null)) {
            save(trigger, inputs, inputCutoffMs, windowStartMs, windowEndMs,
                    ABSTAIN, sensorProperties.fallAi().serverDeciderId(),
                    sensorProperties.fallAi().serverDeciderVersion(), null, null);
            return;
        }
        try {
            FallAssessmentClient.Result result = fallAssessmentClient.assess(request(trigger, inputs, windowStartMs, windowEndMs, inputCutoffMs));
            if (!DECISIONS.contains(result.decision())) {
                log.warn("낙상 AI 응답 거부: memberId={}, batchId={}, errorType={}",
                        trigger.getMember().getId(), trigger.getBatchId(), "InvalidDecision");
                return;
            }
            save(trigger, inputs, inputCutoffMs, windowStartMs, windowEndMs,
                    result.decision(), result.deciderId(), result.deciderVersion(),
                    result.severity(), result.triggerPath());
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("낙상 AI 호출 실패: memberId={}, batchId={}, errorType={}",
                    trigger.getMember().getId(), trigger.getBatchId(), e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> request(
            SensorBatch trigger, List<SensorBatch> inputs, long windowStartMs, long windowEndMs, long inputCutoffMs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", String.valueOf(trigger.getMember().getId()));
        body.put("run_id", trigger.getRunId());
        body.put("window", Map.of("start_ms", windowStartMs, "end_ms", windowEndMs));
        body.put("acc", axis(inputs, "acc", "mg"));
        body.put("gyro", axis(inputs, "gyro", "mrads"));
        body.put("hr", heartRates(trigger.getMember().getId(), windowStartMs, windowEndMs));
        body.put("wear_state", trigger.getWearState());
        Map<String, Object> triggerBody = new LinkedHashMap<>();
        triggerBody.put("kind", trigger.getTriggerKind());
        triggerBody.put("smv_g", trigger.getTriggerSmvG());
        triggerBody.put("ts_ms", trigger.getTriggerTsMs());
        body.put("trigger", triggerBody);
        body.put("input_cutoff_ms", inputCutoffMs);
        return body;
    }

    private Map<String, Object> axis(List<SensorBatch> inputs, String axisName, String valuesName) {
        List<TimedVector> vectors = new ArrayList<>();
        Double requestedFrequency = null;
        for (SensorBatch input : inputs) {
            JsonNode axis = readAxis(input, axisName);
            if (axis == null) {
                continue;
            }
            if (requestedFrequency == null && axis.hasNonNull("fs_hz_requested")) {
                requestedFrequency = axis.get("fs_hz_requested").asDouble();
            }
            long elapsedNs = axis.get("t0_elapsed_ns").asLong();
            JsonNode values = axis.get(valuesName);
            JsonNode dtNs = axis.get("dt_ns");
            for (int index = 0; index < values.size(); index++) {
                if (index > 0) {
                    elapsedNs += dtNs.get(index - 1).asLong();
                }
                List<Integer> vector = new ArrayList<>();
                for (JsonNode value : values.get(index)) {
                    vector.add(value.asInt());
                }
                long epochMs = input.getAnchorEpochMs()
                        + (elapsedNs - input.getAnchorElapsedNs()) / 1_000_000L;
                vectors.add(new TimedVector(epochMs, vector));
            }
        }
        if (vectors.isEmpty()) {
            return null;
        }
        vectors.sort(Comparator.comparingLong(TimedVector::timestampMs));
        List<Long> dtMs = new ArrayList<>();
        List<List<Integer>> values = new ArrayList<>();
        long previousMs = vectors.get(0).timestampMs();
        for (int index = 0; index < vectors.size(); index++) {
            TimedVector vector = vectors.get(index);
            if (index > 0) {
                dtMs.add(vector.timestampMs() - previousMs);
            }
            values.add(vector.values());
            previousMs = vector.timestampMs();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fs_hz_requested", requestedFrequency);
        result.put("t0_ms", vectors.get(0).timestampMs());
        result.put("dt_ms", dtMs);
        if (axisName.equals("acc")) {
            result.put("mg", values);
        } else {
            result.put("mrads", values);
        }
        return result;
    }

    private JsonNode readAxis(SensorBatch input, String axisName) {
        try {
            JsonNode node = objectMapper.readTree(new String(s3Service.downloadBytes(input.getS3Key()), StandardCharsets.UTF_8));
            JsonNode axis = node.get(axisName);
            if (axis == null || axis.isNull()) {
                return null;
            }
            return axis;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("fall input payload is invalid", e);
        }
    }

    private List<Map<String, Object>> heartRates(Long memberId, long windowStartMs, long windowEndMs) {
        LocalDateTime start = LocalDateTime.ofInstant(Instant.ofEpochMilli(windowStartMs), HEART_RATE_ZONE);
        LocalDateTime end = LocalDateTime.ofInstant(Instant.ofEpochMilli(windowEndMs), HEART_RATE_ZONE);
        return heartRateEventRepository.findByMemberIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(memberId, start, end)
                .stream().map(this::heartRate).toList();
    }

    private Map<String, Object> heartRate(HeartRateEvent event) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bpm", event.getHeartRate());
        result.put("ts_ms", event.getMeasuredAt().atZone(HEART_RATE_ZONE).toInstant().toEpochMilli());
        result.put("accuracy", event.getAccuracy());
        return result;
    }

    private record TimedVector(long timestampMs, List<Integer> values) {}

    private void save(
            SensorBatch trigger, List<SensorBatch> inputs, long inputCutoffMs, long windowStartMs, long windowEndMs,
            String output, String deciderId, String deciderVersion, String severity, String triggerPath) {
        long decisionAtMs = System.currentTimeMillis();
        long modelAvailableMaxMs = inputs.stream().mapToLong(SensorBatch::getModelAvailableAtServerMs).max().orElse(0L);
        if (windowEndMs > inputCutoffMs || inputCutoffMs > decisionAtMs || modelAvailableMaxMs > decisionAtMs) {
            throw new IllegalStateException("fall decision causality violated");
        }
        DecisionRecord record = DecisionRecord.builder()
                .decisionId("dec-" + UUID.randomUUID().toString().replace("-", ""))
                .memberId(trigger.getMember().getId())
                .runId(trigger.getRunId())
                .streamIdsUsed(streamIds(inputs))
                .decisionAtMs(decisionAtMs)
                .decisionOutput(output)
                .deciderId(deciderId)
                .deciderVersion(deciderVersion)
                .inputCutoffMs(inputCutoffMs)
                .featureSupportEndMs(windowEndMs)
                .modelAvailableAtServerMaxMs(modelAvailableMaxMs)
                .windowStartMs(windowStartMs)
                .windowEndMs(windowEndMs)
                .severity(severity)
                .triggerPath(triggerPath)
                .triggerBatchId(trigger.getBatchId())
                .build();
        decisionRecordPersistenceService.save(record);
        log.info("낙상 판정 기록: memberId={}, decisionId={}, output={}, batchCount={}",
                record.getMemberId(), record.getDecisionId(), record.getDecisionOutput(), inputs.size());
    }

    private String streamIds(List<SensorBatch> inputs) {
        try {
            return objectMapper.writeValueAsString(inputs.stream().map(SensorBatch::getBatchId).toList());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("decision stream ids serialization failed", e);
        }
    }
}
