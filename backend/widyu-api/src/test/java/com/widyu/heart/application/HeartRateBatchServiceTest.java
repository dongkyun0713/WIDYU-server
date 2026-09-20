package com.widyu.heart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.widyu.decision.DecisionRecord;
import com.widyu.decision.application.DecisionRecordPersistenceService;
import com.widyu.global.properties.SensorProperties;
import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.application.HeartRateAnomalyDetector.DetectionResult;
import com.widyu.heart.repository.HeartRateEventRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.sensor.dto.request.HeartRateBatchRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
@DisplayName("HeartRateBatchService 단위 테스트")
class HeartRateBatchServiceTest {

    private static final Long MEMBER_ID = 1023L;
    private static final String BATCH_ID = "01j8zk3v9x2q4m7n8p1r5s6t7v";
    private static final String RUN_ID = "run-6b1f0c2d";
    // 2025-10-09T17:53:20.123 Asia/Seoul
    private static final long FIRST_TS_MS = 1_760_000_000_123L;
    private static final String REASON = "연속 3회 임계 초과";

    @Mock private HeartRateAnomalyDetector heartRateAnomalyDetector;
    @Mock private HeartRatePersistenceService heartRatePersistenceService;
    @Mock private HeartRateEventRepository heartRateEventRepository;
    @Mock private DecisionRecordPersistenceService decisionRecordPersistenceService;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    @DisplayName("정상 배치를 저장하면 샘플마다 정확도와 배치 ID가 함께 남는다")
    void 정상_배치를_저장하면_샘플마다_정확도와_배치_ID가_함께_남는다() {
        // given
        Member member = member();
        givenNoExistingSamples();
        given(heartRateAnomalyDetector.detect(eq(MEMBER_ID), any(), eq("UNKNOWN")))
                .willReturn(new DetectionResult(HeartRateStatus.NORMAL, false, "NORMAL", REASON));

        // when
        HeartRateBatchService.BatchOutcome outcome = service().storeAndAssess(
                member, BATCH_ID, RUN_ID, List.of(
                        sample(71, FIRST_TS_MS, "HIGH"),
                        sample(72, FIRST_TS_MS + 997, "HIGH"),
                        sample(73, FIRST_TS_MS + 1995, "MEDIUM")),
                System.currentTimeMillis());

        // then
        assertThat(outcome.stored()).isEqualTo(3);
        assertThat(outcome.skipped()).isZero();
        assertThat(outcome.aiSkipped()).isZero();

        ArgumentCaptor<String> accuracy = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> heartRate = ArgumentCaptor.forClass(Integer.class);
        then(heartRatePersistenceService).should(times(3)).saveBatchSample(
                eq(member), heartRate.capture(), any(), eq(HeartRateStatus.NORMAL), eq(false),
                accuracy.capture(), eq(BATCH_ID), isNull());
        assertThat(heartRate.getAllValues()).containsExactly(71, 72, 73);
        assertThat(accuracy.getAllValues()).containsExactly("HIGH", "HIGH", "MEDIUM");
    }

    @Test
    @DisplayName("정상 판정은 판정 행을 만들지 않고 건수만 센다")
    void 정상_판정은_판정_행을_만들지_않고_건수만_센다() {
        // given
        Member member = member();
        givenNoExistingSamples();
        given(heartRateAnomalyDetector.detect(eq(MEMBER_ID), any(), anyString()))
                .willReturn(new DetectionResult(HeartRateStatus.NORMAL, false, "NORMAL", REASON));

        // when
        service().storeAndAssess(
                member, BATCH_ID, RUN_ID, List.of(
                        sample(71, FIRST_TS_MS, "HIGH"),
                        sample(72, FIRST_TS_MS + 997, "HIGH")),
                System.currentTimeMillis());

        // then
        // 하루 8만 행을 만들 이유가 없다. 정상 판정은 heart_rate_event.status에 이미 있다(ADR-0035 결정 1).
        assertThat(meterRegistry.get("heart.decision").tag("output", "NO_ALERT").counter().count())
                .isEqualTo(2.0);
        then(decisionRecordPersistenceService).should(never()).save(any());
    }

    @Test
    @DisplayName("AI가 실패하면 UNKNOWN으로 저장하고 남은 샘플은 AI를 부르지 않는다")
    void AI가_실패하면_UNKNOWN으로_저장하고_남은_샘플은_AI를_부르지_않는다() {
        // given
        Member member = member();
        givenNoExistingSamples();
        willThrow(new RestClientException("AI 연결 실패"))
                .given(heartRateAnomalyDetector).detect(eq(MEMBER_ID), any(), anyString());

        // when
        HeartRateBatchService.BatchOutcome outcome = service().storeAndAssess(
                member, BATCH_ID, RUN_ID, List.of(
                        sample(71, FIRST_TS_MS, "HIGH"),
                        sample(72, FIRST_TS_MS + 997, "HIGH"),
                        sample(73, FIRST_TS_MS + 1995, "HIGH")),
                System.currentTimeMillis());

        // then
        // 판정 누락이 아니라 판정 불가의 기록이다. 세 샘플 모두 저장된다.
        assertThat(outcome.stored()).isEqualTo(3);
        assertThat(outcome.aiSkipped()).isEqualTo(3);
        then(heartRateAnomalyDetector).should(times(1)).detect(eq(MEMBER_ID), any(), anyString());
        then(heartRatePersistenceService).should(times(3)).saveBatchSample(
                eq(member), anyInt(), any(), eq(HeartRateStatus.UNKNOWN), eq(false),
                anyString(), eq(BATCH_ID), isNull());
        // 입력 부족과 판정기 장애를 구별한다. 호출이 실패한 배치는 판정 행을 남기지 않는다(ADR-0033 결정 2).
        then(decisionRecordPersistenceService).should(never()).save(any());
    }

    @Test
    @DisplayName("신뢰할 수 없는 샘플만 있으면 AI를 부르지 않고 판정 보류 한 줄을 남긴다")
    void 신뢰할_수_없는_샘플만_있으면_판정_보류_한_줄을_남긴다() {
        // given
        Member member = member();
        givenNoExistingSamples();
        givenDecisionSaved();

        // when
        HeartRateBatchService.BatchOutcome outcome = service().storeAndAssess(
                member, BATCH_ID, RUN_ID, List.of(
                        sample(0, FIRST_TS_MS, "UNRELIABLE"),
                        sample(0, FIRST_TS_MS + 997, "UNRELIABLE")),
                System.currentTimeMillis());

        // then
        assertThat(outcome.stored()).isEqualTo(2);
        assertThat(outcome.aiSkipped()).isEqualTo(2);
        then(heartRateAnomalyDetector).should(never()).detect(anyLong(), any(), anyString());

        DecisionRecord saved = savedDecision();
        assertThat(saved.getDecisionOutput()).isEqualTo("ABSTAIN_INSUFFICIENT_INPUT");
        assertThat(saved.getDeciderId()).isEqualTo("widyu-server");
        assertThat(saved.getDeciderVersion()).isEqualTo("abstain-v1");
        assertThat(saved.getWindowStartMs()).isEqualTo(FIRST_TS_MS);
        assertThat(saved.getWindowEndMs()).isEqualTo(FIRST_TS_MS + 997);
        assertThat(saved.getFeatureSupportEndMs()).isEqualTo(FIRST_TS_MS + 997);
        assertThat(saved.getHrBpm()).isNull();
        assertThat(saved.getReason()).isNull();
        assertThat(saved.getSeverity()).isNull();
    }

    @Test
    @DisplayName("위급으로 판정하면 판정 행 하나에 필수 필드와 심박 근거가 남는다")
    void 위급으로_판정하면_판정_행_하나에_필수_필드와_심박_근거가_남는다() {
        // given
        Member member = member();
        givenNoExistingSamples();
        givenDecisionSaved();
        given(heartRateAnomalyDetector.detect(eq(MEMBER_ID), any(), anyString()))
                .willReturn(new DetectionResult(HeartRateStatus.EMERGENCY, true, "EMERGENCY", REASON));
        long serverReceivedAtMs = FIRST_TS_MS + 400L;

        // when
        service().storeAndAssess(
                member, BATCH_ID, RUN_ID, List.of(sample(185, FIRST_TS_MS, "HIGH")), serverReceivedAtMs);

        // then
        DecisionRecord saved = savedDecision();
        assertThat(saved.getDecisionId()).startsWith("dec-");
        assertThat(saved.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(saved.getRunId()).isEqualTo(RUN_ID);
        assertThat(saved.getStreamIdsUsed()).isEqualTo("[\"%s\"]".formatted(BATCH_ID));
        assertThat(saved.getDecisionOutput()).isEqualTo("ALERT");
        assertThat(saved.getDeciderId()).isEqualTo("widyu-ai-hr");
        assertThat(saved.getDeciderVersion()).isEqualTo("ver7");
        assertThat(saved.getInputCutoffMs()).isEqualTo(serverReceivedAtMs);
        assertThat(saved.getModelAvailableAtServerMaxMs()).isEqualTo(serverReceivedAtMs);
        assertThat(saved.getFeatureSupportEndMs()).isEqualTo(FIRST_TS_MS);
        assertThat(saved.getWindowStartMs()).isEqualTo(FIRST_TS_MS);
        assertThat(saved.getWindowEndMs()).isEqualTo(FIRST_TS_MS);
        assertThat(saved.getDecisionAtMs()).isNotNull();
        assertThat(saved.getSeverity()).isEqualTo("EMERGENCY");
        assertThat(saved.getTriggerPath()).isNull();
        assertThat(saved.getAlertDelivered()).isFalse();
        assertThat(saved.getHrBpm()).isEqualTo(185);
        assertThat(saved.getHrMeasuredAtMs()).isEqualTo(FIRST_TS_MS);
        assertThat(saved.getHrAccuracy()).isEqualTo("HIGH");
        assertThat(saved.getReason()).isEqualTo(REASON);
    }

    @Test
    @DisplayName("위급으로 판정된 샘플은 위급 표시와 판정 식별자를 달고 저장된다")
    void 위급으로_판정된_샘플은_위급_표시와_판정_식별자를_달고_저장된다() {
        // given
        Member member = member();
        givenNoExistingSamples();
        givenDecisionSaved();
        given(heartRateAnomalyDetector.detect(eq(MEMBER_ID), any(), anyString()))
                .willReturn(new DetectionResult(HeartRateStatus.EMERGENCY, true, "EMERGENCY", REASON));

        // when
        service().storeAndAssess(
                member, BATCH_ID, RUN_ID, List.of(sample(185, FIRST_TS_MS, "HIGH")),
                System.currentTimeMillis());

        // then
        // 알림이 가리킬 판정이 먼저 있어야 도달 사실을 채울 수 있다(LLD-0053 5.2).
        String decisionId = savedDecision().getDecisionId();
        then(heartRatePersistenceService).should().saveBatchSample(
                eq(member), eq(185), any(), eq(HeartRateStatus.EMERGENCY), eq(true),
                eq("HIGH"), eq(BATCH_ID), eq(decisionId));
    }

    @Test
    @DisplayName("판정 기록이 예외를 던져도 심박은 그대로 저장한다")
    void 판정_기록이_예외를_던져도_심박은_그대로_저장한다() {
        // given
        Member member = member();
        givenNoExistingSamples();
        willThrow(new IllegalStateException("판정 기록 저장 실패"))
                .given(decisionRecordPersistenceService).save(any());
        given(heartRateAnomalyDetector.detect(eq(MEMBER_ID), any(), anyString()))
                .willReturn(new DetectionResult(HeartRateStatus.EMERGENCY, true, "EMERGENCY", REASON));

        // when
        HeartRateBatchService.BatchOutcome outcome = service().storeAndAssess(
                member, BATCH_ID, RUN_ID, List.of(sample(185, FIRST_TS_MS, "HIGH")),
                System.currentTimeMillis());

        // then
        assertThat(outcome.stored()).isEqualTo(1);
        then(heartRatePersistenceService).should().saveBatchSample(
                eq(member), eq(185), any(), eq(HeartRateStatus.EMERGENCY), eq(true),
                eq("HIGH"), eq(BATCH_ID), isNull());
    }

    @Test
    @DisplayName("위급을 기록해도 심박 값과 판정 사유는 어떤 로그에도 남지 않는다")
    void 위급을_기록해도_심박_값과_판정_사유는_어떤_로그에도_남지_않는다() {
        // given
        Member member = member();
        givenNoExistingSamples();
        // 무작위 판정 식별자가 우연히 "185"를 품어 검사가 흔들리지 않게 고정한다.
        willAnswer(invocation -> {
            DecisionRecord record = invocation.getArgument(0);
            ReflectionTestUtils.setField(record, "decisionId", "dec-cafe");
            return record;
        }).given(decisionRecordPersistenceService).save(any());
        given(heartRateAnomalyDetector.detect(eq(MEMBER_ID), any(), anyString()))
                .willReturn(new DetectionResult(HeartRateStatus.EMERGENCY, true, "EMERGENCY", REASON));
        Logger logger = (Logger) LoggerFactory.getLogger(HeartRateBatchService.class);
        Level oldLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when
            service().storeAndAssess(
                    member, BATCH_ID, RUN_ID, List.of(sample(185, FIRST_TS_MS, "HIGH")),
                    System.currentTimeMillis());

            // then
            // 사유와 심박 값이 사는 곳은 판정 기록 행뿐이다(정책 1.5.5·1.5.11).
            assertThat(appender.list).isNotEmpty();
            for (ILoggingEvent event : appender.list) {
                assertThat(event.getFormattedMessage())
                        .doesNotContain(REASON)
                        .doesNotContain("185")
                        .doesNotContain("EMERGENCY");
            }
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(oldLevel);
            appender.stop();
        }
    }

    @Test
    @DisplayName("이미 저장된 측정 시각의 샘플은 건너뛴다")
    void 이미_저장된_측정_시각의_샘플은_건너뛴다() {
        // given
        Member member = member();
        given(heartRateEventRepository.existsByMemberIdAndMeasuredAt(eq(MEMBER_ID), any()))
                .willReturn(true, false);
        given(heartRateAnomalyDetector.detect(eq(MEMBER_ID), any(), anyString()))
                .willReturn(new DetectionResult(HeartRateStatus.NORMAL, false, "NORMAL", REASON));

        // when
        HeartRateBatchService.BatchOutcome outcome = service().storeAndAssess(
                member, BATCH_ID, RUN_ID, List.of(
                        sample(71, FIRST_TS_MS, "HIGH"),
                        sample(72, FIRST_TS_MS + 997, "HIGH")),
                System.currentTimeMillis());

        // then
        // 재전송이나 단건 경로로 이미 들어온 시각이다. UK (member_id, measured_at)와 같은 판정이다.
        assertThat(outcome.stored()).isEqualTo(1);
        assertThat(outcome.skipped()).isEqualTo(1);
        then(heartRatePersistenceService).should(times(1)).saveBatchSample(
                any(), anyInt(), any(), any(), anyBoolean(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("마지막으로 저장한 샘플로 최신값을 갱신한다")
    void 마지막으로_저장한_샘플로_최신값을_갱신한다() {
        // given
        Member member = member();
        givenNoExistingSamples();
        given(heartRateAnomalyDetector.detect(eq(MEMBER_ID), any(), anyString()))
                .willReturn(new DetectionResult(HeartRateStatus.NORMAL, false, "NORMAL", REASON));

        // when
        // 순서가 뒤섞여 들어와도 잰 시각 순으로 처리한다.
        service().storeAndAssess(
                member, BATCH_ID, RUN_ID, List.of(
                        sample(72, FIRST_TS_MS + 997, "HIGH"),
                        sample(71, FIRST_TS_MS, "HIGH")),
                System.currentTimeMillis());

        // then
        ArgumentCaptor<LocalDateTime> measuredAt = ArgumentCaptor.forClass(LocalDateTime.class);
        then(heartRatePersistenceService).should().updateLatestResult(
                eq(MEMBER_ID), eq(HeartRateStatus.NORMAL), eq(72), measuredAt.capture());
        assertThat(measuredAt.getValue())
                .isEqualTo(LocalDateTime.of(2025, 10, 9, 17, 53, 21, 120_000_000));
    }

    @Test
    @DisplayName("저장한 샘플이 없으면 최신값을 갱신하지 않는다")
    void 저장한_샘플이_없으면_최신값을_갱신하지_않는다() {
        // given
        Member member = member();
        givenDecisionSaved();
        given(heartRateEventRepository.existsByMemberIdAndMeasuredAt(eq(MEMBER_ID), any()))
                .willReturn(true);

        // when
        HeartRateBatchService.BatchOutcome outcome = service().storeAndAssess(
                member, BATCH_ID, RUN_ID, List.of(sample(71, FIRST_TS_MS, "HIGH")),
                System.currentTimeMillis());

        // then
        assertThat(outcome.stored()).isZero();
        then(heartRatePersistenceService).should(never())
                .updateLatestResult(anyLong(), any(), anyInt(), any());
    }

    private HeartRateBatchService service() {
        return new HeartRateBatchService(
                heartRateAnomalyDetector, heartRatePersistenceService, heartRateEventRepository,
                decisionRecordPersistenceService, properties(), meterRegistry);
    }

    private SensorProperties properties() {
        return new SensorProperties(
                32_768, null, null,
                new SensorProperties.FallAi(false, "/api/fall", 2, "widyu-server", "abstain-v1"),
                new SensorProperties.HeartAi("widyu-ai-hr", "ver7"));
    }

    /** 저장 서비스는 받은 행을 그대로 돌려준다. 식별자는 서비스가 붙이므로 그대로 흘려보낸다. */
    private void givenDecisionSaved() {
        willAnswer(invocation -> invocation.getArgument(0))
                .given(decisionRecordPersistenceService).save(any());
    }

    private DecisionRecord savedDecision() {
        ArgumentCaptor<DecisionRecord> captor = ArgumentCaptor.forClass(DecisionRecord.class);
        then(decisionRecordPersistenceService).should().save(captor.capture());
        return captor.getValue();
    }

    private void givenNoExistingSamples() {
        given(heartRateEventRepository.existsByMemberIdAndMeasuredAt(eq(MEMBER_ID), any()))
                .willReturn(false);
    }

    private Member member() {
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        ReflectionTestUtils.setField(member, "id", MEMBER_ID);
        return member;
    }

    private HeartRateBatchRequest.Sample sample(int bpm, long tsMs, String accuracy) {
        return HeartRateBatchRequest.Sample.of(bpm, tsMs, accuracy);
    }
}
