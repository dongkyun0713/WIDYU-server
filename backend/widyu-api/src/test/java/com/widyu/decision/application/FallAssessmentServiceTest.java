package com.widyu.decision.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.decision.DecisionRecord;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.global.properties.SensorProperties;
import com.widyu.heart.repository.HeartRateEventRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.sensor.SensorBatch;
import com.widyu.sensor.repository.SensorBatchRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("FallAssessmentService 단위 테스트")
class FallAssessmentServiceTest {

    @Mock private SensorBatchRepository sensorBatchRepository;
    @Mock private HeartRateEventRepository heartRateEventRepository;
    @Mock private FallAssessmentClient fallAssessmentClient;
    @Mock private DecisionRecordPersistenceService decisionRecordPersistenceService;
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

    private FallAssessmentService service(boolean enabled) {
        return new FallAssessmentService(
                properties(enabled), sensorBatchRepository, heartRateEventRepository, fallAssessmentClient,
                decisionRecordPersistenceService, s3Service, new ObjectMapper());
    }

    private SensorProperties properties(boolean enabled) {
        SensorProperties.Export export = new SensorProperties.Export(
                5000L, 15, "test-build", java.util.Map.of(), java.util.Map.of(),
                new SensorProperties.Export.Clock("DEVICE_MONOTONIC", "UTC_EPOCH_MS", "ANCHOR_PAIR", "TEST"),
                "NO_DEVICE", "NO_DATA", "NOT_IMPLEMENTED");
        return new SensorProperties(
                32768, null, export, new SensorProperties.FallAi(enabled, "/api/fall", 2, "server", "v1"));
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
}
