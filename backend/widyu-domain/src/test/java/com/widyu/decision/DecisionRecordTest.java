package com.widyu.decision;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DecisionRecordTest {

    @Test
    @DisplayName("판정을 만들면 알림은 아직 도달하지 않은 것으로 남는다")
    void 판정을_만들면_알림은_아직_도달하지_않은_것으로_남는다() {
        // when
        DecisionRecord record = alert();

        // then
        assertThat(record.getAlertDelivered()).isFalse();
        assertThat(record.getAlertId()).isNull();
        assertThat(record.getAlertAtMs()).isNull();
    }

    @Test
    @DisplayName("전송이 성공하면 도달 사실과 첫 성공 시각을 남긴다")
    void 전송이_성공하면_도달_사실과_첫_성공_시각을_남긴다() {
        // given
        DecisionRecord record = alert();

        // when
        record.markDelivered("fcm-7", 1_760_000_000_500L);

        // then
        assertThat(record.getAlertDelivered()).isTrue();
        assertThat(record.getAlertId()).isEqualTo("fcm-7");
        assertThat(record.getAlertAtMs()).isEqualTo(1_760_000_000_500L);
    }

    @Test
    @DisplayName("보호자가 여럿이라 전송이 여러 번 성공해도 첫 성공만 남는다")
    void 보호자가_여럿이라_전송이_여러_번_성공해도_첫_성공만_남는다() {
        // given
        DecisionRecord record = alert();
        record.markDelivered("fcm-7", 1_760_000_000_500L);

        // when
        record.markDelivered("fcm-8", 1_760_000_003_000L);

        // then
        // 「언제 알림이 갔는가」는 첫 성공 하나다(ADR-0035 결정 3).
        assertThat(record.getAlertId()).isEqualTo("fcm-7");
        assertThat(record.getAlertAtMs()).isEqualTo(1_760_000_000_500L);
        assertThat(record.getAlertDelivered()).isTrue();
    }

    private DecisionRecord alert() {
        return DecisionRecord.builder()
                .decisionId("dec-01")
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
