package com.widyu.fcm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.widyu.decision.DecisionRecord;
import com.widyu.decision.repository.DecisionRecordRepository;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.FcmOutbox;
import com.widyu.fcm.MemberFcmToken;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.repository.FcmNotificationRepository;
import com.widyu.fcm.repository.FcmOutboxRepository;
import com.widyu.fcm.repository.MemberFcmTokenRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("FcmOutboxTransactions 완료 처리 단위 테스트")
class FcmOutboxTransactionsTest {

    private static final Long ROW_ID = 7L;
    private static final long FENCE = 3L;
    private static final String TOKEN = "synthetic-token";
    private static final String DECISION_ID = "dec-01";

    @Mock private FcmOutboxRepository outbox;
    @Mock private FcmNotificationRepository notifications;
    @Mock private FcmEligibility eligibility;
    @Mock private MemberFcmTokenRepository tokens;
    @Mock private DecisionRecordRepository decisions;

    @Test
    @DisplayName("전송이 성공하면 그 판정에 알림 도달 사실이 채워진다")
    void 전송이_성공하면_그_판정에_알림_도달_사실이_채워진다() {
        // given
        FcmOutbox row = row(DECISION_ID);
        DecisionRecord record = alert();
        given(outbox.lockById(ROW_ID)).willReturn(Optional.of(row));
        given(decisions.findByDecisionId(DECISION_ID)).willReturn(Optional.of(record));

        // when
        transactions().finish(delivery(), FcmTransport.Result.delivered());

        // then
        assertThat(row.getState()).isEqualTo(FcmOutbox.State.SENT);
        assertThat(record.getAlertDelivered()).isTrue();
        assertThat(record.getAlertId()).isEqualTo("fcm-7");
        assertThat(record.getAlertAtMs()).isNotNull();
    }

    @Test
    @DisplayName("판정에서 나오지 않은 알림은 전송에 성공해도 판정을 찾지 않는다")
    void 판정에서_나오지_않은_알림은_전송에_성공해도_판정을_찾지_않는다() {
        // given
        FcmOutbox row = row(null);
        given(outbox.lockById(ROW_ID)).willReturn(Optional.of(row));

        // when
        transactions().finish(delivery(), FcmTransport.Result.delivered());

        // then
        assertThat(row.getState()).isEqualTo(FcmOutbox.State.SENT);
        then(decisions).should(never()).findByDecisionId(anyString());
    }

    @Test
    @DisplayName("전송이 실패하면 판정은 도달하지 않은 채로 남는다")
    void 전송이_실패하면_판정은_도달하지_않은_채로_남는다() {
        // given
        FcmOutbox row = row(DECISION_ID);
        DecisionRecord record = alert();
        given(outbox.lockById(ROW_ID)).willReturn(Optional.of(row));

        // when
        transactions().finish(delivery(), FcmTransport.Result.retry(Duration.ofSeconds(30)));

        // then
        assertThat(record.getAlertDelivered()).isFalse();
        assertThat(record.getAlertAtMs()).isNull();
        then(decisions).should(never()).findByDecisionId(anyString());
        then(notifications).should(never()).save(any());
    }

    private FcmOutboxTransactions transactions() {
        return new FcmOutboxTransactions(outbox, notifications, eligibility, properties(), tokens, decisions);
    }

    private FcmDeliveryProperties properties() {
        return new FcmDeliveryProperties(
                3, Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofSeconds(60), Duration.ofSeconds(30));
    }

    private FcmDelivery delivery() {
        FcmSendDto message = new FcmSendDto(
                "제목", "본문", FcmCategory.HEART_MESSAGE, "", null, true, 1023L, Map.of(), DECISION_ID);
        return new FcmDelivery(ROW_ID, FENCE, TOKEN, message, java.time.Instant.now().plusSeconds(600));
    }

    /** 선점이 끝난 직후의 행. 완료 처리는 이 상태에서만 행을 건드린다. */
    private FcmOutbox row(String decisionId) {
        LocalDateTime now = LocalDateTime.now();
        Member member = Member.createMember(MemberType.GUARDIAN, "보호자", "01011112222");
        return FcmOutbox.builder()
                .id(ROW_ID)
                .recipientMember(member)
                .memberFcmToken(MemberFcmToken.builder().token(TOKEN).member(member).active(true).build())
                .relatedMemberId(1023L)
                .title("제목")
                .body("본문")
                .fcmCategory(FcmCategory.HEART_MESSAGE)
                .emergency(true)
                .state(FcmOutbox.State.CLAIMED)
                .attempts(1)
                .fence(FENCE)
                .availableAt(now.minusSeconds(1))
                .expiresAt(now.plusMinutes(5))
                .leaseUntil(now.plusMinutes(1))
                .decisionId(decisionId)
                .build();
    }

    private DecisionRecord alert() {
        return DecisionRecord.builder()
                .decisionId(DECISION_ID)
                .memberId(1023L)
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
