package com.widyu.decision.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.decision.DecisionRecord;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.global.properties.SensorProperties;
import com.widyu.heart.repository.HeartRateEventRepository;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.application.IncidentService;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.sensor.SensorBatch;
import com.widyu.sensor.repository.SensorBatchRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
@DisplayName("FallAssessmentService 단위 테스트")
class FallAssessmentServiceTest {

    @Mock private SensorBatchRepository sensorBatchRepository;
    @Mock private HeartRateEventRepository heartRateEventRepository;
    @Mock private FallAssessmentClient fallAssessmentClient;
    @Mock private DecisionRecordPersistenceService decisionRecordPersistenceService;
    @Mock private IncidentService incidentService;
    @Mock private S3Service s3Service;

    @Test
    @DisplayName("판정 기능이 꺼져 있으면 입력 조회와 기록을 하지 않는다")
    void 판정_기능이_꺼져_있으면_입력_조회와_기록을_하지_않는다() {
        // when
        service(false).assessAfterImpact(trigger());

        // then
        then(sensorBatchRepository).should(never()).findFallInputBatches(
                anyLong(), any(), anyLong(), anyLong(), anyLong());
        then(decisionRecordPersistenceService).should(never()).save(any(DecisionRecord.class));
    }

    @Test
    @DisplayName("창에 가속도 입력이 없으면 AI를 호출하지 않고 ABSTAIN을 기록한다")
    void 창에_가속도_입력이_없으면_ABSTAIN을_기록한다() {
        // given
        SensorBatch trigger = trigger();
        given(sensorBatchRepository.findFallInputBatches(anyLong(), any(), anyLong(), anyLong(), anyLong()))
                .willReturn(List.of(trigger));

        // when
        service(true).assessAfterImpact(trigger);

        // then
        ArgumentCaptor<DecisionRecord> captor = ArgumentCaptor.forClass(DecisionRecord.class);
        then(decisionRecordPersistenceService).should().save(captor.capture());
        DecisionRecord saved = captor.getValue();
        assertThat(saved.getDecisionOutput()).isEqualTo("ABSTAIN_INSUFFICIENT_INPUT");
        assertThat(saved.getAlertDelivered()).isFalse();
        assertThat(saved.getTriggerBatchId()).isEqualTo("01j8zimu000000000000000001");
        then(fallAssessmentClient).should(never()).assess(any());
    }

    @Test
    @DisplayName("가속도 두 배치가 있으면 AI 요청과 인과성 필드를 남긴다")
    void 가속도_두_배치가_있으면_AI_요청과_인과성_필드를_남긴다() {
        // given
        SensorBatch first = accelerationBatch("01j8zimu000000000000000001", "sensor/first.json", 2_120L);
        SensorBatch second = accelerationBatch("01j8zimu000000000000000002", "sensor/second.json", 2_180L);
        given(sensorBatchRepository.findFallInputBatches(anyLong(), any(), anyLong(), anyLong(), anyLong()))
                .willReturn(List.of(first, second));
        given(s3Service.downloadBytes("sensor/first.json")).willReturn(accPayload());
        given(s3Service.downloadBytes("sensor/second.json")).willReturn(accPayload());
        given(heartRateEventRepository.findByMemberIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(anyLong(), any(), any()))
                .willReturn(List.of());
        given(fallAssessmentClient.assess(any()))
                .willReturn(new FallAssessmentClient.Result("NO_ALERT", "fall-ai", "2026.09", null, "IMPACT"));

        // when
        service(true).assessAfterImpact(first);

        // then
        ArgumentCaptor<Map<String, Object>> request = mapCaptor();
        then(fallAssessmentClient).should().assess(request.capture());
        assertThat(request.getValue().get("gyro")).isNull();
        assertThat(request.getValue().get("acc")).isNotNull();

        ArgumentCaptor<DecisionRecord> record = ArgumentCaptor.forClass(DecisionRecord.class);
        then(decisionRecordPersistenceService).should().save(record.capture());
        DecisionRecord saved = record.getValue();
        assertThat(saved.getDecisionOutput()).isEqualTo("NO_ALERT");
        assertThat(saved.getStreamIdsUsed())
                .isEqualTo("[\"01j8zimu000000000000000001\",\"01j8zimu000000000000000002\"]");
        assertThat(saved.getModelAvailableAtServerMaxMs()).isEqualTo(2_180L);
        assertThat(saved.getFeatureSupportEndMs()).isLessThanOrEqualTo(saved.getInputCutoffMs());
        assertThat(saved.getInputCutoffMs()).isLessThanOrEqualTo(saved.getDecisionAtMs());
        assertThat(saved.getModelAvailableAtServerMaxMs()).isLessThanOrEqualTo(saved.getDecisionAtMs());
        assertThat(saved.getAlertDelivered()).isFalse();
    }

    @Test
    @DisplayName("AI가 위급으로 판정하면 그 판정으로 본인확인 사건이 열린다")
    void AI가_위급으로_판정하면_그_판정으로_본인확인_사건이_열린다() {
        // given
        SensorBatch input = accelerationBatch("01j8zimu000000000000000001", "sensor/input.json", 2_120L);
        given(sensorBatchRepository.findFallInputBatches(anyLong(), any(), anyLong(), anyLong(), anyLong()))
                .willReturn(List.of(input));
        given(s3Service.downloadBytes("sensor/input.json")).willReturn(accPayload());
        given(heartRateEventRepository.findByMemberIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(anyLong(), any(), any()))
                .willReturn(List.of());
        given(fallAssessmentClient.assess(any()))
                .willReturn(new FallAssessmentClient.Result("ALERT", "fall-ai", "2026.09", "HIGH", "IMPACT"));

        // when
        service(true).assessAfterImpact(input);

        // then
        ArgumentCaptor<DecisionRecord> decision = ArgumentCaptor.forClass(DecisionRecord.class);
        then(incidentService).should().openForAlert(decision.capture(), eq(IncidentKind.FALL_SUSPECTED));
        assertThat(decision.getValue().getDecisionOutput()).isEqualTo("ALERT");
        assertThat(decision.getValue().getSeverity()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("위급이 아닌 판정은 본인확인 사건을 열지 않는다")
    void 위급이_아닌_판정은_본인확인_사건을_열지_않는다() {
        // given
        SensorBatch trigger = trigger();
        given(sensorBatchRepository.findFallInputBatches(anyLong(), any(), anyLong(), anyLong(), anyLong()))
                .willReturn(List.of(trigger));

        // when
        service(true).assessAfterImpact(trigger);

        // then
        then(incidentService).should(never()).openForAlert(any(), any());
    }

    @Test
    @DisplayName("AI 호출이 예외를 던지면 판정 기록을 남기지 않는다")
    void AI_호출이_예외를_던지면_판정_기록을_남기지_않는다() {
        // given
        SensorBatch input = accelerationBatch("01j8zimu000000000000000001", "sensor/input.json", 2_120L);
        given(sensorBatchRepository.findFallInputBatches(anyLong(), any(), anyLong(), anyLong(), anyLong()))
                .willReturn(List.of(input));
        given(s3Service.downloadBytes("sensor/input.json")).willReturn(accPayload());
        given(heartRateEventRepository.findByMemberIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(anyLong(), any(), any()))
                .willReturn(List.of());
        willThrow(new RestClientException("unavailable")).given(fallAssessmentClient).assess(any());

        // when
        service(true).assessAfterImpact(input);

        // then
        then(decisionRecordPersistenceService).should(never()).save(any(DecisionRecord.class));
    }

    private FallAssessmentService service(boolean enabled) {
        return new FallAssessmentService(
                properties(enabled), sensorBatchRepository, heartRateEventRepository, fallAssessmentClient,
                decisionRecordPersistenceService, incidentService, s3Service, new ObjectMapper());
    }

    private SensorProperties properties(boolean enabled) {
        SensorProperties.Export export = new SensorProperties.Export(
                5000L, 900_000L, 15, "test-build", java.util.Map.of(), java.util.Map.of(),
                new SensorProperties.Export.Clock("DEVICE_MONOTONIC", "UTC_EPOCH_MS", "ANCHOR_PAIR", "TEST"),
                "NO_DEVICE", "NO_DATA");
        return new SensorProperties(
                32768, null, export, new SensorProperties.FallAi(enabled, "/api/fall", 2, "server", "v1"),
                new SensorProperties.HeartAi("widyu-ai-hr", "ver7"),
                new SensorProperties.Incident(45, 5000L));
    }

    private SensorBatch trigger() {
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        ReflectionTestUtils.setField(member, "id", 1L);
        return SensorBatch.builder()
                .batchId("01j8zimu000000000000000001")
                .member(member)
                .stream("imu_watch")
                .source("watch")
                .deviceId("gw-3f2a")
                .sessionId("s-1")
                .seq(1L)
                .bootId("b1")
                .clockMappingId("cm-1")
                .anchorElapsedNs(1L)
                .anchorEpochMs(1000L)
                .uncertaintyMs(1.0)
                .measuredAtStartMs(1000L)
                .measuredAtEndMs(2000L)
                .serverReceivedAtMs(2100L)
                .acceptedAtMs(2110L)
                .persistedAtMs(2120L)
                .modelAvailableAtServerMs(2120L)
                .onBody(true)
                .qualityStatus("OK")
                .triggerKind("impact")
                .triggerSmvG(3.0)
                .triggerTsMs(1900L)
                .gyroBackfill(false)
                .isResend(false)
                .s3Key("sensor/test.json")
                .byteSize(10)
                .payloadSha256("a".repeat(64))
                .configMismatch(false)
                .build();
    }

    private SensorBatch accelerationBatch(String batchId, String s3Key, long modelAvailableAtServerMs) {
        SensorBatch batch = trigger();
        ReflectionTestUtils.setField(batch, "batchId", batchId);
        ReflectionTestUtils.setField(batch, "s3Key", s3Key);
        ReflectionTestUtils.setField(batch, "accN", 1);
        ReflectionTestUtils.setField(batch, "modelAvailableAtServerMs", modelAvailableAtServerMs);
        return batch;
    }

    private byte[] accPayload() {
        return """
                {"acc":{"fs_hz_requested":50,"t0_elapsed_ns":"1","dt_ns":[],"mg":[[20,-980,110]]},"gyro":null}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Map.class);
    }
}
