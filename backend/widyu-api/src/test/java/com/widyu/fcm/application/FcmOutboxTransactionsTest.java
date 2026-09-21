package com.widyu.fcm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.widyu.decision.repository.DecisionRecordRepository;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.FcmNotification;
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
import org.mockito.ArgumentCaptor;
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
        given(outbox.lockById(ROW_ID)).willReturn(Optional.of(row));

        // when
        transactions().finish(delivery(), FcmTransport.Result.delivered());

        // then
        // 보호자가 여럿이면 이 트랜잭션이 동시에 여럿 돈다. 읽고 나서 쓰면 나중 것이 앞선 시각을 덮으므로
        // 「비어 있을 때만」 조건을 UPDATE 문에 넣은 원자적 갱신 하나로 간다(ADR-0035 결정 3).
        assertThat(row.getState()).isEqualTo(FcmOutbox.State.SENT);
        ArgumentCaptor<FcmNotification> notification = ArgumentCaptor.forClass(FcmNotification.class);
        then(notifications).should().save(notification.capture());
        assertThat(notification.getValue().getDecisionId()).isEqualTo(DECISION_ID);
        ArgumentCaptor<Long> alertAtMs = ArgumentCaptor.forClass(Long.class);
        then(decisions).should().markDeliveredIfFirst(
                eq(DECISION_ID), eq("fcm-7"), alertAtMs.capture());
        assertThat(alertAtMs.getValue()).isPositive();
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
        ArgumentCaptor<FcmNotification> notification = ArgumentCaptor.forClass(FcmNotification.class);
        then(notifications).should().save(notification.capture());
        assertThat(notification.getValue().getDecisionId()).isNull();
        then(decisions).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("전송이 실패하면 판정은 도달하지 않은 채로 남는다")
    void 전송이_실패하면_판정은_도달하지_않은_채로_남는다() {
        // given
        FcmOutbox row = row(DECISION_ID);
        given(outbox.lockById(ROW_ID)).willReturn(Optional.of(row));

        // when
        transactions().finish(delivery(), FcmTransport.Result.retry(Duration.ofSeconds(30)));

        // then
        assertThat(row.getState()).isEqualTo(FcmOutbox.State.PENDING);
        then(decisions).shouldHaveNoInteractions();
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
}
