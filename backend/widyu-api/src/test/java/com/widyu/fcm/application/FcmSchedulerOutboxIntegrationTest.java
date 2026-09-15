package com.widyu.fcm.application;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.FcmOutbox;
import com.widyu.fcm.MemberFcmToken;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.event.goal.healthschedule.listener.HealthScheduleNotificationListener;
import com.widyu.fcm.event.goal.walk.listener.WalkNotificationListener;
import com.widyu.fcm.repository.*;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.global.util.MemberUtil;
import com.widyu.goal.healthschedule.repository.HealthScheduleRepository;
import com.widyu.goal.walk.repository.WalkRepository;
import com.widyu.healthschedule.HealthSchedule;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import com.widyu.walk.Walk;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@DataJpaTest(properties = {"fcm.delivery.max-retries=2", "fcm.delivery.normal-ttl=1h", "fcm.delivery.emergency-ttl=1m"})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaAuditingConfig.class, FcmService.class, FcmOutboxService.class, FcmOutboxTransactions.class,
        FcmEligibility.class, NotificationSettingService.class, FcmDeliveryProperties.class,
        WalkNotificationListener.class, HealthScheduleNotificationListener.class})
class FcmSchedulerOutboxIntegrationTest {
    @Autowired WalkNotificationListener walkScheduler;
    @Autowired HealthScheduleNotificationListener healthScheduler;
    @Autowired WalkRepository walks;
    @Autowired HealthScheduleRepository schedules;
    @Autowired MemberRepository members;
    @Autowired MemberFcmTokenRepository tokens;
    @Autowired FcmOutboxRepository outbox;
    @Autowired FcmNotificationRepository notifications;
    @Autowired FcmOutboxTransactions transactions;
    @Autowired PlatformTransactionManager transactionManager;
    @SpyBean FcmService fcm;
    @MockBean FcmOutboxDispatcher immediate;
    @MockBean FcmTransport transport;
    @MockBean JPAQueryFactory queryFactory;
    @MockBean MemberUtil memberUtil;

    @AfterEach
    void cleanup() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            notifications.deleteAll();
            outbox.deleteAll();
            walks.deleteAll();
            schedules.deleteAllInBatch();
            tokens.deleteAll();
            members.deleteAll();
        });
    }

    @Test
    @DisplayName("걷기 스케줄러가 실행되면 쓰기 트랜잭션으로 outbox를 커밋하고 송신한다")
    void 걷기_스케줄러가_outbox를_커밋한다() {
        // given
        createWalk();
        observeEnqueue(false);
        // when: 테스트 트랜잭션 없이 실제 스케줄러 프록시가 쓰기 트랜잭션을 열어야 한다.
        walkScheduler.sendWalkGoalReminderToUnachieved();
        // then
        assertCommittedAndDispatch(FcmCategory.WALK);
    }

    @Test
    @DisplayName("건강 일정 스케줄러가 실행되면 쓰기 트랜잭션으로 outbox를 커밋하고 송신한다")
    void 건강일정_스케줄러가_outbox를_커밋한다() {
        // given
        createSchedule();
        observeEnqueue(false);
        // when
        healthScheduler.sendHealthScheduleReminder();
        // then
        assertCommittedAndDispatch(FcmCategory.HEALTH_SCHEDULE);
    }

    @Test
    @DisplayName("걷기 스케줄러의 커밋이 실패하면 outbox와 송신을 남기지 않는다")
    void 걷기_스케줄러_롤백은_송신하지_않는다() {
        // given
        createWalk();
        observeEnqueue(true);
        // when / then
        assertThatThrownBy(() -> walkScheduler.sendWalkGoalReminderToUnachieved())
                .isInstanceOf(IllegalStateException.class).hasMessage("scheduler commit failure");
        assertRolledBackWithoutDispatch();
    }

    @Test
    @DisplayName("건강 일정 스케줄러의 커밋이 실패하면 outbox와 송신을 남기지 않는다")
    void 건강일정_스케줄러_롤백은_송신하지_않는다() {
        // given
        createSchedule();
        observeEnqueue(true);
        // when / then
        assertThatThrownBy(() -> healthScheduler.sendHealthScheduleReminder())
                .isInstanceOf(IllegalStateException.class).hasMessage("scheduler commit failure");
        assertRolledBackWithoutDispatch();
    }

    private void observeEnqueue(boolean failCommit) {
        willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            invocation.callRealMethod();
            assertThat(outbox.count()).isEqualTo(1);
            then(immediate).shouldHaveNoInteractions();
            then(transport).shouldHaveNoInteractions();
            if (failCommit) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("scheduler commit failure");
                    }
                });
            }
            return null;
        }).given(fcm).sendMessageToUser(anyLong(), any(FcmSendDto.class));
    }

    private void assertCommittedAndDispatch(FcmCategory category) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(outbox.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getState()).isEqualTo(FcmOutbox.State.PENDING);
            assertThat(row.getFcmCategory()).isEqualTo(category);
        });
        Long id = outbox.findAll().getFirst().getId();
        then(immediate).should().submit(id);
        given(transport.send(any(FcmDelivery.class), any())).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(outbox.findById(id).orElseThrow().getState()).isEqualTo(FcmOutbox.State.CLAIMED);
            BooleanSupplier preflight = invocation.getArgument(1);
            assertThat(preflight.getAsBoolean()).isTrue();
            return FcmTransport.Result.delivered();
        });
        FcmOutboxDispatcher dispatcher = dispatcher();
        try {
            dispatcher.dispatch(id);
        } finally {
            dispatcher.shutdown();
        }
        assertThat(outbox.findById(id).orElseThrow().getState()).isEqualTo(FcmOutbox.State.SENT);
        assertThat(notifications.count()).isEqualTo(1);
    }

    private void assertRolledBackWithoutDispatch() {
        assertThat(outbox.count()).isZero();
        assertThat(notifications.count()).isZero();
        then(immediate).shouldHaveNoInteractions();
        FcmOutboxDispatcher dispatcher = dispatcher();
        try {
            dispatcher.poll();
        } finally {
            dispatcher.shutdown();
        }
        then(transport).shouldHaveNoInteractions();
    }

    private FcmOutboxDispatcher dispatcher() {
        return new FcmOutboxDispatcher(transactions, outbox, transport, new FcmSendMetrics(new SimpleMeterRegistry()));
    }

    private void createWalk() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                walks.save(Walk.createWithGoal(memberWithToken(), LocalDate.now(), 10000)));
    }

    private void createSchedule() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                schedules.save(HealthSchedule.create(memberWithToken(), "병원 방문", "주소", 37.0, 127.0,
                        LocalDateTime.now().plusHours(1).plusMinutes(5))));
    }

    private Member memberWithToken() {
        Member member = members.save(Member.createMember(MemberType.SENIOR, "수신자", "01012345678"));
        tokens.save(MemberFcmToken.builder().member(member).token("loopback-only").active(true).build());
        return member;
    }
}
