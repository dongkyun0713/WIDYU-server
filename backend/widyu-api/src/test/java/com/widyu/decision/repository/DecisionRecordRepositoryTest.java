package com.widyu.decision.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.decision.DecisionRecord;
import com.widyu.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
@DisplayName("DecisionRecordRepository 알림 도달 갱신 테스트")
class DecisionRecordRepositoryTest {

    private static final String DECISION_ID = "dec-01";

    @Autowired private DecisionRecordRepository decisionRecordRepository;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @Test
    @DisplayName("첫 전송 성공을 기록하면 도달 여부와 알림 식별자와 시각이 채워진다")
    void 첫_전송_성공을_기록하면_도달_여부와_알림_식별자와_시각이_채워진다() {
        // given
        decisionRecordRepository.save(alert());

        // when
        int updated = decisionRecordRepository.markDeliveredIfFirst(DECISION_ID, "fcm-7", 1_760_000_000_500L);

        // then
        assertThat(updated).isEqualTo(1);
        DecisionRecord found = decisionRecordRepository.findByDecisionId(DECISION_ID).orElseThrow();
        assertThat(found.getAlertDelivered()).isTrue();
        assertThat(found.getAlertId()).isEqualTo("fcm-7");
        assertThat(found.getAlertAtMs()).isEqualTo(1_760_000_000_500L);
    }

    @Test
    @DisplayName("두 번째 전송 성공을 기록해도 첫 알림 식별자와 시각이 그대로 남는다")
    void 두_번째_전송_성공을_기록해도_첫_알림_식별자와_시각이_그대로_남는다() {
        // given
        decisionRecordRepository.save(alert());
        decisionRecordRepository.markDeliveredIfFirst(DECISION_ID, "fcm-7", 1_760_000_000_500L);

        // when
        int updated = decisionRecordRepository.markDeliveredIfFirst(DECISION_ID, "fcm-8", 1_760_000_003_000L);

        // then
        // 「언제 알림이 갔는가」는 첫 성공 하나다. 보호자가 여럿이어도 나중 성공이 앞선 시각을 덮지 않는다.
        assertThat(updated).isZero();
        DecisionRecord found = decisionRecordRepository.findByDecisionId(DECISION_ID).orElseThrow();
        assertThat(found.getAlertId()).isEqualTo("fcm-7");
        assertThat(found.getAlertAtMs()).isEqualTo(1_760_000_000_500L);
        assertThat(found.getAlertDelivered()).isTrue();
    }

    @Test
    @DisplayName("없는 판정을 기록하면 아무 행도 바뀌지 않는다")
    void 없는_판정을_기록하면_아무_행도_바뀌지_않는다() {
        // given
        decisionRecordRepository.save(alert());

        // when
        int updated = decisionRecordRepository.markDeliveredIfFirst("dec-없음", "fcm-7", 1_760_000_000_500L);

        // then
        assertThat(updated).isZero();
        assertThat(decisionRecordRepository.findByDecisionId(DECISION_ID).orElseThrow().getAlertDelivered())
                .isFalse();
    }

    private DecisionRecord alert() {
        return DecisionRecord.builder()
                .decisionId(DECISION_ID)
                .memberId(1023L)
                .runId("run-01")
                .streamIdsUsed("[\"01j8zk3v9x2q4m7n8p1r5s6t7v\"]")
                .decisionAtMs(1_760_000_000_400L)
                .decisionOutput("ALERT")
                .deciderId("widyu-ai-hr")
                .deciderVersion("ver7")
                .inputCutoffMs(1_760_000_000_300L)
                .featureSupportEndMs(1_760_000_000_123L)
                .modelAvailableAtServerMaxMs(1_760_000_000_300L)
                .windowStartMs(1_760_000_000_123L)
                .windowEndMs(1_760_000_000_123L)
                .severity("EMERGENCY")
                .triggerBatchId("01j8zk3v9x2q4m7n8p1r5s6t7v")
                .hrBpm(185)
                .hrMeasuredAtMs(1_760_000_000_123L)
                .hrAccuracy("HIGH")
                .reason("연속 3회 임계 초과")
                .build();
    }
}
