package com.widyu.fcm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.widyu.decision.repository.DecisionRecordRepository;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.FcmOutbox;
import com.widyu.fcm.MemberFcmToken;
import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.degraded.DegradedAlertMarker;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.repository.FcmOutboxRepository;
import com.widyu.fcm.repository.MemberFcmTokenRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("FcmOutboxTransactions 완료 처리 단위 테스트")
class FcmOutboxTransactionsTest {

    private static final Long ROW_ID = 7L;
    private static final long FENCE = 3L;
    private static final String TOKEN = "synthetic-token";
    private static final String DECISION_ID = "dec-01";

    @Mock private FcmOutboxRepository outbox;
    @Mock private FcmEligibility eligibility;
    @Mock private MemberFcmTokenRepository tokens;
    @Mock private DecisionRecordRepository decisions;
    @Mock private StringRedisTemplate redis;

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
    }

    @Test
    @DisplayName("재시도할 수 없는 전송 실패면 받는 사람과 관련 회원을 담아 최종 실패 로그를 남긴다")
    void 재시도할_수_없는_전송_실패면_받는_사람과_관련_회원을_담아_최종_실패_로그를_남긴다() {
        // given
        FcmOutbox row = row(null);
        ReflectionTestUtils.setField(row.getRecipientMember(), "id", 12L);
        given(outbox.lockById(ROW_ID)).willReturn(Optional.of(row));
        Logger logger = (Logger) LoggerFactory.getLogger(FcmOutboxTransactions.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when
            transactions().finish(delivery(), FcmTransport.Result.rejected(false));

            // then
            assertThat(row.getState()).isEqualTo(FcmOutbox.State.EXHAUSTED);
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                    "[FCM_FAILED] Type=HEART_MESSAGE Emergency=true Related=1023 Recipient=12 Attempts=1 Reason=REJECTED");
            assertThat(appender.list.get(0).getFormattedMessage()).doesNotContain(TOKEN, "제목", "본문");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("재시도를 기다리다 만료된 알림을 선점하면 만료 사유로 최종 실패 로그를 남긴다")
    void 재시도를_기다리다_만료된_알림을_선점하면_만료_사유로_최종_실패_로그를_남긴다() {
        // given
        FcmOutbox row = row(null);
        ReflectionTestUtils.setField(row, "state", FcmOutbox.State.PENDING);
        ReflectionTestUtils.setField(row, "expiresAt", LocalDateTime.now().minusSeconds(1));
        given(outbox.lockById(ROW_ID)).willReturn(Optional.of(row));
        Logger logger = (Logger) LoggerFactory.getLogger(FcmOutboxTransactions.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when
            FcmDelivery delivery = transactions().claim(ROW_ID);

            // then
            assertThat(delivery).isNull();
            assertThat(row.getState()).isEqualTo(FcmOutbox.State.EXPIRED);
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).singleElement()
                    .asString().startsWith("[FCM_FAILED] Type=HEART_MESSAGE Emergency=true").endsWith("Reason=EXPIRED");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("다시 보낼 전송 실패면 최종 실패 로그를 남기지 않는다")
    void 다시_보낼_전송_실패면_최종_실패_로그를_남기지_않는다() {
        // given
        FcmOutbox row = row(null);
        given(outbox.lockById(ROW_ID)).willReturn(Optional.of(row));
        Logger logger = (Logger) LoggerFactory.getLogger(FcmOutboxTransactions.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when
            transactions().finish(delivery(), FcmTransport.Result.retry(Duration.ofSeconds(30)));

            // then
            assertThat(row.getState()).isEqualTo(FcmOutbox.State.PENDING);
            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("타입 알림을 선점하면 저장된 타입과 data를 복원한다")
    void 타입_알림을_선점하면_저장된_타입과_data를_복원한다() {
        // given
        FcmOutbox row = row(null);
        ReflectionTestUtils.setField(row, "state", FcmOutbox.State.PENDING);
        ReflectionTestUtils.setField(row, "notificationType", NotificationType.HEART_RATE_EMERGENCY);
        ReflectionTestUtils.setField(row, "dataPayload", "{\"eventId\":\"event-1\",\"type\":\"HEART_RATE_EMERGENCY\"}");
        given(outbox.lockById(ROW_ID)).willReturn(Optional.of(row));
        given(eligibility.eligible(row)).willReturn(true);

        // when
        FcmDelivery delivery = transactions().claim(ROW_ID);

        // then
        assertThat(delivery.message().notificationType()).isEqualTo(NotificationType.HEART_RATE_EMERGENCY);
        assertThat(delivery.message().data()).containsEntry("eventId", "event-1");
        assertThat(row.getState()).isEqualTo(FcmOutbox.State.CLAIMED);
    }

    @Test
    @DisplayName("이전 outbox 행에 data 한 키만 있으면 존재하는 키를 복원한다")
    void 이전_outbox_행에_data_한_키만_있으면_존재하는_키를_복원한다() {
        // given
        FcmOutbox typeOnly = row(null);
        ReflectionTestUtils.setField(typeOnly, "dataType", "MEDICATION_SCHEDULE_CHANGED");
        FcmOutbox revisionOnly = row(null);
        ReflectionTestUtils.setField(revisionOnly, "dataRevision", 42L);

        // when / then
        assertThat(FcmDelivery.from(typeOnly).message().data())
                .containsExactlyInAnyOrderEntriesOf(Map.of("type", "MEDICATION_SCHEDULE_CHANGED"));
        assertThat(FcmDelivery.from(revisionOnly).message().data())
                .containsExactlyInAnyOrderEntriesOf(Map.of("revision", "42"));
    }

    @Test
    @DisplayName("푸시 전용 본인확인 outbox를 복원하면 센터 ID를 만들지 않는다")
    void 푸시_전용_본인확인_outbox는_센터_ID를_만들지_않는다() {
        // given
        FcmOutbox row = row(null);
        ReflectionTestUtils.setField(row, "notificationType", NotificationType.SAFETY_SELF_CHECK);

        // when
        FcmDelivery delivery = FcmDelivery.from(row);

        // then
        assertThat(delivery.notificationId()).isNull();
    }

    @Test
    @DisplayName("저장된 data JSON이 손상되면 빈 data로 전송하지 않고 예외가 발생한다")
    void 저장된_data_JSON이_손상되면_예외가_발생한다() {
        // given
        FcmOutbox row = row(null);
        ReflectionTestUtils.setField(row, "dataPayload", "{invalid");

        // when / then
        assertThatThrownBy(() -> FcmDelivery.from(row)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("손상된 data를 선점하면 재시도 없이 소진 상태로 바꾼다")
    void 손상된_data를_선점하면_소진_상태로_바꾼다() {
        // given
        FcmOutbox row = row(null);
        ReflectionTestUtils.setField(row, "state", FcmOutbox.State.PENDING);
        ReflectionTestUtils.setField(row, "notificationType", NotificationType.SAFETY_SELF_CHECK);
        ReflectionTestUtils.setField(row, "dataPayload", "{invalid");
        given(outbox.lockById(ROW_ID)).willReturn(Optional.of(row));
        given(eligibility.eligible(row)).willReturn(true);

        // when
        FcmDelivery delivery = transactions().claim(ROW_ID);

        // then
        assertThat(delivery).isNull();
        assertThat(row.getState()).isEqualTo(FcmOutbox.State.EXHAUSTED);
    }

    // 저하 경로 중복 차단 (LLD-0086 5.5)

    @Test
    @DisplayName("저하 경로 표식이 있는 시니어의 심박 위급 행을 선점하면 취소하고 중복 차단 수를 센다")
    void 저하경로_표식이_있는_시니어의_심박위급_행을_선점하면_취소한다() {
        // given
        FcmOutbox row = pendingEmergency(NotificationType.HEART_RATE_EMERGENCY);
        given(outbox.lockById(ROW_ID)).willReturn(Optional.of(row));
        given(redis.hasKey("fcm:degraded:alerted:1023")).willReturn(true);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        // when
        FcmDelivery delivery = transactions(meterRegistry).claim(ROW_ID);

        // then
        assertThat(delivery).isNull();
        assertThat(row.getState()).isEqualTo(FcmOutbox.State.CANCELLED);
        assertThat(meterRegistry.counter("fcm.degraded.deduped").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("저하 경로 표식을 읽지 못하면 심박 위급 행을 그대로 선점한다")
    void 저하경로_표식을_읽지_못하면_심박위급_행을_그대로_선점한다() {
        // given
        FcmOutbox row = pendingEmergency(NotificationType.HEART_RATE_EMERGENCY);
        given(outbox.lockById(ROW_ID)).willReturn(Optional.of(row));
        given(redis.hasKey("fcm:degraded:alerted:1023")).willThrow(new RedisConnectionFailureException("down"));
        given(eligibility.eligible(row)).willReturn(true);

        // when
        FcmDelivery delivery = transactions().claim(ROW_ID);

        // then
        assertThat(delivery).isNotNull();
        assertThat(row.getState()).isEqualTo(FcmOutbox.State.CLAIMED);
    }

    @Test
    @DisplayName("심박 위급이 아닌 알림은 저하 경로 표식과 무관하게 선점한다")
    void 심박위급이_아닌_알림은_저하경로_표식과_무관하게_선점한다() {
        // given
        FcmOutbox general = pendingEmergency(NotificationType.ALBUM_CREATED);
        ReflectionTestUtils.setField(general, "emergency", false);
        FcmOutbox selfHelp = pendingEmergency(NotificationType.SELF_HELP_REQUESTED);
        given(outbox.lockById(ROW_ID)).willReturn(Optional.of(general), Optional.of(selfHelp));
        given(eligibility.eligible(general)).willReturn(true);
        given(eligibility.eligible(selfHelp)).willReturn(true);

        // when
        FcmOutboxTransactions transactions = transactions();
        FcmDelivery generalDelivery = transactions.claim(ROW_ID);
        FcmDelivery selfHelpDelivery = transactions.claim(ROW_ID);

        // then
        assertThat(generalDelivery).isNotNull();
        assertThat(selfHelpDelivery).isNotNull();
        assertThat(general.getState()).isEqualTo(FcmOutbox.State.CLAIMED);
        assertThat(selfHelp.getState()).isEqualTo(FcmOutbox.State.CLAIMED);
        then(redis).shouldHaveNoInteractions();
    }

    private FcmOutboxTransactions transactions() {
        return transactions(new SimpleMeterRegistry());
    }

    private FcmOutboxTransactions transactions(SimpleMeterRegistry meterRegistry) {
        return new FcmOutboxTransactions(outbox, eligibility, properties(), tokens, decisions,
                new DegradedAlertMarker(redis, properties(), meterRegistry));
    }

    /** 선점 전 대기 중인 심박 위급 행. 관련 회원(시니어)은 1023이다. */
    private FcmOutbox pendingEmergency(NotificationType type) {
        FcmOutbox row = row(null);
        ReflectionTestUtils.setField(row, "state", FcmOutbox.State.PENDING);
        ReflectionTestUtils.setField(row, "notificationType", type);
        return row;
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
