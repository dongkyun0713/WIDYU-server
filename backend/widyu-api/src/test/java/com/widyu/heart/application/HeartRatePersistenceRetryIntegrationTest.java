package com.widyu.heart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.decision.DecisionRecord;
import com.widyu.decision.repository.DecisionRecordRepository;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.MemberFcmToken;
import com.widyu.fcm.application.FcmDeliveryProperties;
import com.widyu.fcm.application.FcmEligibility;
import com.widyu.fcm.application.FcmOutboxDispatcher;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.application.FirebaseProperties;
import com.widyu.fcm.application.NotificationSettingService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.event.heart.dto.HeartRateEmergencyEvent;
import com.widyu.fcm.repository.FcmOutboxRepository;
import com.widyu.fcm.repository.FcmNotificationRepository;
import com.widyu.fcm.repository.MemberFcmTokenRepository;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.global.retry.TransientLockRetryListener;
import com.widyu.global.util.MemberUtil;
import com.widyu.heart.HeartRateEmergency;
import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.dto.request.HeartRateSingleRequest;
import com.widyu.heart.repository.HeartRateEmergencyRepository;
import com.widyu.heart.repository.HeartRateEventRepository;
import com.widyu.heart.repository.HeartRateResultRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 락 충돌 재시도가 실제 트랜잭션 위에서 한 번만 저장하고 한 번만 알리는지 확인한다(LLD-0076 7절).
 *
 * <p>위급 기록 저장에서 락 충돌을 일으킨다. 그 앞의 판정 기록·심박 이벤트 INSERT가 이미 실행된 뒤라,
 * 실패한 시도가 남긴 것이 롤백으로 지워지는지와 재시도가 판정 기록을 다시 저장할 수 있는지를 함께 본다.
 * 판정 기록은 첫 시도의 INSERT로 IDENTITY id가 채워진 채 재시도에 다시 넘어온다. Hibernate가 없는 행의
 * merge를 새 행 삽입으로 처리하기 때문에 저장된다(LLD-0076 5절 3). 이 동작이 바뀌면 배치 재시도 테스트가 실패한다.
 * 위급 알림 리스너는 실제 리스너처럼 같은 트랜잭션에서 outbox를 쌓는 대역으로 바꾼다.
 */
@DataJpaTest(properties = {"fcm.delivery.max-retries=5", "fcm.delivery.normal-ttl=24h", "fcm.delivery.emergency-ttl=5m"})
@ActiveProfiles("test")
@EnableConfigurationProperties({FcmDeliveryProperties.class, FirebaseProperties.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaAuditingConfig.class, HeartRatePersistenceService.class, FcmOutboxService.class, FcmEligibility.class,
        NotificationSettingService.class, HeartRatePersistenceRetryIntegrationTest.RetryTestConfig.class})
@DisplayName("심박 저장 락 충돌 재시도 통합 테스트")
class HeartRatePersistenceRetryIntegrationTest {

    private static final String BATCH_ID = "01j8zk3v9x2q4m7n8p1r5s6t7v";
    private static final LocalDateTime MEASURED_AT = LocalDateTime.of(2026, 10, 4, 10, 0);

    @Autowired private HeartRatePersistenceService heartRatePersistenceService;
    @Autowired private HeartRateEventRepository heartRateEventRepository;
    @Autowired private DecisionRecordRepository decisionRecordRepository;
    @Autowired private FcmOutboxRepository outboxRepository;
    @Autowired private FcmNotificationRepository fcmNotificationRepository;
    @Autowired private MemberFcmTokenRepository tokenRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    @SpyBean private HeartRateEmergencyRepository heartRateEmergencyRepository;
    @MockBean private HeartRateResultRepository heartRateResultRepository;
    @MockBean private FcmOutboxDispatcher dispatcher;
    @MockBean private JPAQueryFactory queryFactory;
    @MockBean private MemberUtil memberUtil;

    @AfterEach
    void cleanup() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outboxRepository.deleteAll();
            fcmNotificationRepository.deleteAll();
            tokenRepository.deleteAll();
            heartRateEmergencyRepository.deleteAll();
            heartRateEventRepository.deleteAll();
            decisionRecordRepository.deleteAll();
            memberRepository.deleteAll();
        });
        // 컨텍스트가 테스트 사이에 재사용되므로 카운터를 비워 각 테스트가 자기 시도만 세게 한다.
        meterRegistry.clear();
    }

    @Test
    @DisplayName("배치 위급 저장이 락 충돌 뒤 재시도로 성공하면 기록과 알림이 한 건씩만 남는다")
    void 배치_위급_저장이_락_충돌_뒤_재시도로_성공하면_기록과_알림이_한_건씩만_남는다() {
        // given
        Member senior = seniorWithToken();
        failFirstEmergencySaveWithLockConflict();

        // when
        heartRatePersistenceService.saveBatchSample(
                senior, 185, MEASURED_AT, HeartRateStatus.EMERGENCY, true, "HIGH", BATCH_ID, alert("dec-retry"));

        // then
        assertThat(heartRateEventRepository.count()).isEqualTo(1);
        assertThat(heartRateEmergencyRepository.count()).isEqualTo(1);
        assertThat(decisionRecordRepository.count()).isEqualTo(1);
        assertThat(decisionRecordRepository.findByDecisionId("dec-retry")).isPresent();
        assertThat(outboxRepository.count()).isEqualTo(1);
        Long outboxId = outboxRepository.findAll().getFirst().getId();
        then(dispatcher).should(times(1)).submit(anyLong());
        then(dispatcher).should().submit(outboxId);
        assertThat(counter("attempt_failed", "HeartRatePersistenceService.saveBatchSample")).isEqualTo(1.0);
        assertThat(counter("exhausted", "HeartRatePersistenceService.saveBatchSample")).isZero();
    }

    @Test
    @DisplayName("단건 위급 저장이 락 충돌 뒤 재시도로 성공하면 기록과 알림이 한 건씩만 남는다")
    void 단건_위급_저장이_락_충돌_뒤_재시도로_성공하면_기록과_알림이_한_건씩만_남는다() {
        // given
        Member senior = seniorWithToken();
        failFirstEmergencySaveWithLockConflict();

        // when
        heartRatePersistenceService.saveMeasurement(
                senior.getId(), HeartRateSingleRequest.of(185, MEASURED_AT, "서울시", null),
                HeartRateStatus.EMERGENCY, true);

        // then
        assertThat(heartRateEventRepository.count()).isEqualTo(1);
        assertThat(heartRateEmergencyRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isEqualTo(1);
        then(dispatcher).should(times(1)).submit(anyLong());
    }

    @Test
    @DisplayName("락 충돌이 세 번 이어지면 예외를 전파하고 기록과 알림을 남기지 않는다")
    void 락_충돌이_세_번_이어지면_예외를_전파하고_기록과_알림을_남기지_않는다() {
        // given
        Member senior = seniorWithToken();
        willThrow(new CannotAcquireLockException("deadlock"))
                .given(heartRateEmergencyRepository).save(any(HeartRateEmergency.class));

        // when
        assertThatThrownBy(() -> heartRatePersistenceService.saveBatchSample(
                senior, 185, MEASURED_AT, HeartRateStatus.EMERGENCY, true, "HIGH", BATCH_ID, alert("dec-fail")))
                .isInstanceOf(CannotAcquireLockException.class);

        // then
        then(heartRateEmergencyRepository).should(times(3)).save(any(HeartRateEmergency.class));
        assertThat(heartRateEventRepository.count()).isZero();
        assertThat(heartRateEmergencyRepository.count()).isZero();
        assertThat(decisionRecordRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
        then(dispatcher).shouldHaveNoInteractions();
        assertThat(counter("attempt_failed", "HeartRatePersistenceService.saveBatchSample")).isEqualTo(3.0);
        assertThat(counter("exhausted", "HeartRatePersistenceService.saveBatchSample")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("제약 위반이면 재시도하지 않고 한 번만 시도한다")
    void 제약_위반이면_재시도하지_않고_한_번만_시도한다() {
        // given
        Member senior = seniorWithToken();
        willThrow(new DataIntegrityViolationException("duplicate"))
                .given(heartRateEmergencyRepository).save(any(HeartRateEmergency.class));

        // when
        assertThatThrownBy(() -> heartRatePersistenceService.saveBatchSample(
                senior, 185, MEASURED_AT, HeartRateStatus.EMERGENCY, true, "HIGH", BATCH_ID, alert("dec-dup")))
                .isInstanceOf(DataIntegrityViolationException.class);

        // then
        then(heartRateEmergencyRepository).should(times(1)).save(any(HeartRateEmergency.class));
        assertThat(heartRateEventRepository.count()).isZero();
        assertThat(counter("attempt_failed", "HeartRatePersistenceService.saveBatchSample")).isZero();
    }

    /**
     * 첫 위급 저장만 락 충돌로 실패시키고 이후는 실제로 저장한다. 리포지토리 스파이는 JDK 프록시라
     * 실제 메서드 호출을 쓸 수 없어, 같은 트랜잭션의 엔티티 매니저로 직접 저장한다.
     */
    private void failFirstEmergencySaveWithLockConflict() {
        AtomicInteger calls = new AtomicInteger();
        willAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                throw new CannotAcquireLockException("deadlock");
            }
            HeartRateEmergency emergency = invocation.getArgument(0);
            entityManager.persist(emergency);
            return emergency;
        }).given(heartRateEmergencyRepository).save(any(HeartRateEmergency.class));
    }

    private double counter(String outcome, String method) {
        return meterRegistry.counter("db.transient.retry", "outcome", outcome, "method", method).count();
    }

    private Member seniorWithToken() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Member member = memberRepository.save(Member.createMember(MemberType.SENIOR, "시니어", "01012345678"));
            tokenRepository.save(MemberFcmToken.builder().member(member).token("token-" + UUID.randomUUID()).active(true).build());
            return member;
        });
    }

    private DecisionRecord alert(String decisionId) {
        return DecisionRecord.builder()
                .decisionId(decisionId)
                .memberId(1L)
                .streamIdsUsed("[\"" + BATCH_ID + "\"]")
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
                .triggerBatchId(BATCH_ID)
                .hrBpm(185)
                .hrMeasuredAtMs(1_760_000_000_123L)
                .hrAccuracy("HIGH")
                .reason("연속 3회 임계 초과")
                .build();
    }

    @TestConfiguration
    @EnableRetry
    static class RetryTestConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        TransientLockRetryListener transientLockRetryListener(MeterRegistry meterRegistry) {
            return new TransientLockRetryListener(meterRegistry);
        }

        @Bean
        EmergencyOutboxListener emergencyOutboxListener(FcmOutboxService outboxService) {
            return new EmergencyOutboxListener(outboxService);
        }
    }

    /** 실제 위급 알림 리스너처럼 발행한 트랜잭션 안에서 outbox를 쌓는다. 가족 조회는 이 테스트의 관심사가 아니다. */
    static class EmergencyOutboxListener {

        private final FcmOutboxService outboxService;

        EmergencyOutboxListener(FcmOutboxService outboxService) {
            this.outboxService = outboxService;
        }

        @EventListener
        public void on(HeartRateEmergencyEvent event) {
            outboxService.enqueue(event.memberId(), FcmSendDto.builder()
                    .title("심박 이상")
                    .content("확인해 주세요")
                    .fcmCategory(FcmCategory.HEART_MESSAGE)
                    .scheme("")
                    .emergency(true)
                    .decisionId(event.decisionId())
                    .build());
        }
    }
}
