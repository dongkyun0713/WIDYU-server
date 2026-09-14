package com.widyu.fcm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.*;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.fcm.*;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.repository.*;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(properties = {"fcm.delivery.max-retries=5", "fcm.delivery.normal-ttl=24h", "fcm.delivery.emergency-ttl=5m"})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaAuditingConfig.class, FcmOutboxService.class, FcmOutboxTransactions.class,
        FcmEligibility.class, NotificationSettingService.class, FcmDeliveryProperties.class})
class FcmOutboxIntegrationTest {
    @Autowired FcmOutboxService service;
    @Autowired FcmOutboxTransactions transactions;
    @Autowired FcmOutboxRepository outbox;
    @Autowired FcmNotificationRepository notifications;
    @Autowired MemberFcmTokenRepository tokens;
    @Autowired MemberRepository members;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean FcmOutboxDispatcher immediate;
    @MockBean JPAQueryFactory queryFactory;
    @MockBean MemberUtil memberUtil;

    @AfterEach
    void cleanup() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            notifications.deleteAll();
            outbox.deleteAll();
            tokens.deleteAll();
            members.deleteAll();
        });
    }

    @Test
    @DisplayName("업무가 커밋되면 즉시 작업을 제출하고 DB 트랜잭션 밖에서 송신한다")
    void 업무_커밋후_트랜잭션밖에서_송신한다() {
        // given
        Long member = memberWithToken();
        FcmTransport transport = (token, message) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(outbox.count()).isEqualTo(1);
            return FcmTransport.Result.delivered();
        };
        // when
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.enqueue(member, message());
            then(immediate).should(never()).submit(anyLong());
        });
        Long id = outbox.findAll().getFirst().getId();
        then(immediate).should().submit(id);
        FcmOutboxDispatcher dispatcher = new FcmOutboxDispatcher(transactions, outbox, transport,
                new FcmSendMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        try { dispatcher.dispatch(id); } finally { dispatcher.shutdown(); }
        // then
        assertThat(outbox.findById(id).orElseThrow().getState()).isEqualTo(FcmOutbox.State.SENT);
        assertThat(notifications.findNotificationsWithCursor(member, null, PageRequest.of(0, 10))).hasSize(1);
    }

    @Test
    @DisplayName("업무가 롤백되면 outbox와 최초 송신 요청을 남기지 않는다")
    void 업무_롤백시_큐와_송신이_없다() {
        // given
        Long member = memberWithToken();
        // when
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.enqueue(member, message());
            throw new IllegalStateException("business rollback");
        })).isInstanceOf(IllegalStateException.class);
        // then
        assertThat(outbox.count()).isZero();
        then(immediate).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("동시에 claim하면 하나의 워커만 발송 권한을 얻는다")
    void 동시_claim은_하나만_획득한다() throws Exception {
        // given
        Long id = queued();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Callable<FcmDelivery> claim = () -> { start.await(); return transactions.claim(id); };
            Future<FcmDelivery> one = executor.submit(claim);
            Future<FcmDelivery> two = executor.submit(claim);
            // when
            start.countDown();
            FcmDelivery first = one.get(5, TimeUnit.SECONDS);
            FcmDelivery second = two.get(5, TimeUnit.SECONDS);
            // then
            assertThat(java.util.stream.Stream.of(first, second).filter(java.util.Objects::nonNull).count()).isEqualTo(1);
            assertThat(outbox.findById(id).orElseThrow().getAttempts()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("만료 lease를 회수하면 이전 워커의 finalize를 무시한다")
    void lease_회수후_이전_finalize를_무시한다() {
        // given
        Long id = queued();
        FcmDelivery old = transactions.claim(id);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            FcmOutbox row = outbox.findById(id).orElseThrow();
            ReflectionTestUtils.setField(row, "leaseUntil", LocalDateTime.now().minusSeconds(1));
        });
        // when
        FcmDelivery current = transactions.claim(id);
        transactions.finish(old, FcmTransport.Result.delivered());
        // then
        assertThat(notifications.count()).isZero();
        assertThat(current.fence()).isGreaterThan(old.fence());
        transactions.finish(current, FcmTransport.Result.delivered());
        transactions.finish(current, FcmTransport.Result.delivered());
        assertThat(notifications.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("429 응답이면 Retry After 이후에만 재시도할 수 있다")
    void 응답_429면_재시도_시각을_영속화한다() {
        // given
        Long id = queued();
        FcmDelivery delivery = transactions.claim(id);
        LocalDateTime earliest = LocalDateTime.now().plusMinutes(2);
        // when
        transactions.finish(delivery, FcmTransport.Result.retry(Duration.ofMinutes(2)));
        // then
        FcmOutbox row = outbox.findById(id).orElseThrow();
        assertThat(row.getState()).isEqualTo(FcmOutbox.State.PENDING);
        assertThat(row.getAvailableAt()).isAfterOrEqualTo(earliest);
        assertThat(transactions.claim(id)).isNull();
        assertThat(notifications.count()).isZero();
    }

    @Test
    @DisplayName("영구 토큰 오류이면 토큰을 비활성화하고 작업을 종료한다")
    void 영구_토큰_오류면_비활성화한다() {
        // given
        Long id = queued();
        FcmDelivery delivery = transactions.claim(id);
        // when
        transactions.finish(delivery, FcmTransport.Result.rejected(true));
        // then
        assertThat(tokens.findAll()).allMatch(token -> !token.isActive());
        assertThat(outbox.findById(id).orElseThrow().getState()).isEqualTo(FcmOutbox.State.EXHAUSTED);
    }

    @Test
    @DisplayName("발송 후 토큰 소유자가 바뀌어도 새 알림은 원수신자에게만 노출한다")
    void 소유자_전환후에도_신규이력은_원수신자에게만_보인다() {
        // given
        Long original = memberWithToken();
        service.enqueue(original, message());
        Long id = outbox.findAll().getFirst().getId();
        FcmDelivery delivery = transactions.claim(id);
        Long other = transferToken();
        // when
        transactions.finish(delivery, FcmTransport.Result.delivered());
        // then
        assertThat(notifications.findNotificationsWithCursor(original, null, PageRequest.of(0, 10))).hasSize(1);
        assertThat(notifications.findNotificationsWithCursor(other, null, PageRequest.of(0, 10))).isEmpty();
        Long notification = notifications.findAll().getFirst().getId();
        assertThat(notifications.findByIdAndMemberFcmToken_MemberId(notification, other)).isEmpty();
        assertThat(notifications.countByMemberFcmToken_MemberIdAndIsReadFalse(original)).isEqualTo(1);
        assertThat(notifications.countByMemberFcmToken_MemberIdAndIsReadFalse(other)).isZero();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                notifications.markAllAsReadByMemberId(other));
        assertThat(notifications.findById(notification).orElseThrow().isRead()).isFalse();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                notifications.markAllAsReadByMemberId(original));
        assertThat(notifications.findById(notification).orElseThrow().isRead()).isTrue();
    }

    @Test
    @DisplayName("토큰 소유자가 바뀐 뒤 영구 오류가 오면 새 소유자의 토큰을 비활성화하지 않는다")
    void 소유자_변경후_늦은_오류는_토큰을_유지한다() {
        // given
        Long id = queued();
        FcmDelivery delivery = transactions.claim(id);
        transferToken();
        // when
        transactions.finish(delivery, FcmTransport.Result.rejected(true));
        // then
        assertThat(tokens.findAll()).allMatch(MemberFcmToken::isActive);
    }

    @Test
    @DisplayName("TTL이 만료되면 HTTP 발송 권한 없이 작업을 만료한다")
    void TTL_만료시_발송하지_않는다() {
        // given
        Long id = queued();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                ReflectionTestUtils.setField(outbox.findById(id).orElseThrow(), "expiresAt", LocalDateTime.now().minusSeconds(1)));
        // when
        FcmDelivery claim = transactions.claim(id);
        // then
        assertThat(claim).isNull();
        assertThat(outbox.findById(id).orElseThrow().getState()).isEqualTo(FcmOutbox.State.EXPIRED);
    }

    @Test
    @DisplayName("재시도 한도를 소진하면 다음 claim을 발급하지 않는다")
    void 재시도_한도를_소진하면_종료한다() {
        // given
        Long id = queued();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                ReflectionTestUtils.setField(outbox.findById(id).orElseThrow(), "attempts", 6));
        // when
        FcmDelivery claim = transactions.claim(id);
        // then
        assertThat(claim).isNull();
        assertThat(outbox.findById(id).orElseThrow().getState()).isEqualTo(FcmOutbox.State.EXHAUSTED);
    }

    private Long memberWithToken() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Member member = members.save(Member.createMember(MemberType.SENIOR, "수신자", "01012345678"));
            tokens.save(MemberFcmToken.builder().member(member).token("mock-token").active(true).build());
            return member.getId();
        });
    }

    @Test
    @DisplayName("인증 대기 중 소유자가 바뀌면 preflight에서 취소한다")
    void 인증_대기중_소유자가_바뀌면_취소한다() {
        // given
        Long id = queued();
        FcmDelivery delivery = transactions.claim(id);
        transferToken();
        // when
        boolean allowed = transactions.preflight(delivery);
        transactions.finish(delivery, FcmTransport.Result.rejected(false));
        // then
        assertThat(allowed).isFalse();
        assertThat(outbox.findById(id).orElseThrow().getState()).isEqualTo(FcmOutbox.State.CANCELLED);
        assertThat(notifications.count()).isZero();
    }

    @Test
    @DisplayName("수신자 불명 이력은 계정 전환 후에도 숨기고 원본을 보존한다")
    void 기존_이력은_앱에서_숨기고_DB에_보존한다() {
        // given
        Long member = memberWithToken();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> notifications.save(
                FcmNotification.builder().memberFcmToken(tokens.findAll().getFirst())
                        .title("기존제목").body("기존본문").fcmCategory(FcmCategory.ALBUM).build()));
        // when
        Long other = transferToken();
        Long notificationId = notifications.findAll().getFirst().getId();
        for (Long viewer : List.of(member, other)) {
            assertThat(notifications.findNotificationsWithCursor(viewer, null, PageRequest.of(0, 10))).isEmpty();
            assertThat(notifications.findNotificationsByCategoryWithCursor(
                    viewer, FcmCategory.ALBUM, null, PageRequest.of(0, 10))).isEmpty();
            assertThat(notifications.findByIdAndMemberFcmToken_MemberId(notificationId, viewer)).isEmpty();
            assertThat(notifications.countByMemberFcmToken_MemberIdAndIsReadFalse(viewer)).isZero();
            assertThat(notifications.countByMemberFcmToken_MemberIdAndFcmCategoryAndIsReadFalse(
                    viewer, FcmCategory.ALBUM)).isZero();
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    notifications.markAllAsReadByMemberId(viewer));
        }
        // then
        List<FcmNotification> history = notifications.findAll();
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().getRecipientMember()).isNull();
        assertThat(history.getFirst().getBody()).isEqualTo("기존본문");
        assertThat(history.getFirst().isRead()).isFalse();
    }

    private Long transferToken() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Member other = members.save(Member.createMember(MemberType.GUARDIAN, "다른회원", "01087654321"));
            tokens.findAll().getFirst().transferTo(other);
            return other.getId();
        });
    }

    @Test
    @DisplayName("인증 대기 중 TTL이 지나면 preflight에서 만료로 종료한다")
    void 인증_대기중_TTL이_지나면_만료한다() {
        // given
        Long id = queued();
        FcmDelivery delivery = transactions.claim(id);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                ReflectionTestUtils.setField(outbox.findById(id).orElseThrow(), "expiresAt", LocalDateTime.now().minusSeconds(1)));
        // when
        boolean allowed = transactions.preflight(delivery);
        // then
        assertThat(allowed).isFalse();
        assertThat(outbox.findById(id).orElseThrow().getState()).isEqualTo(FcmOutbox.State.EXPIRED);
    }

    private Long queued() {
        service.enqueue(memberWithToken(), message());
        return outbox.findAll().getFirst().getId();
    }

    private FcmSendDto message() {
        return new FcmSendDto("제목", "본문", FcmCategory.ALBUM, "", null);
    }
}
