package com.widyu.heart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.fcm.application.degraded.DegradedEmergencyAlertService;
import com.widyu.fcm.event.heart.dto.HeartRateEmergencyEvent;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.application.HeartRateAnomalyDetector.DetectionResult;
import com.widyu.heart.dto.request.HeartRateSingleRequest;
import com.widyu.heart.dto.response.HeartRateStatusResponse;
import com.widyu.heart.repository.HeartRateEmergencyRepository;
import com.widyu.heart.repository.HeartRateEventRepository;
import com.widyu.heart.repository.HeartRateLatestResultWriter;
import com.widyu.heart.repository.HeartRateResultRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Redis 최신값 쓰기가 실패해도 MySQL이 정상이면 단건 위급 심박이 저장되고 알림 신호가 나가는지 실제 트랜잭션에서 본다(LLD-0102).
 * AI 판정기·Redis 최신값·저하 경로만 대역이고 심박 저장은 H2 위의 실제 리포지토리다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaAuditingConfig.class, HeartRateService.class, HeartRatePersistenceService.class,
        HeartRateLatestWriteFailureTest.Config.class})
@DisplayName("Redis 최신값 쓰기 실패 시 단건 심박 저장 통합 테스트")
class HeartRateLatestWriteFailureTest {

    private static final LocalDateTime MEASURED_AT = LocalDateTime.of(2026, 10, 22, 3, 0);

    @Autowired private HeartRateService heartRateService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private HeartRateEventRepository heartRateEventRepository;
    @Autowired private HeartRateEmergencyRepository heartRateEmergencyRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private EmergencyEvents emergencyEvents;
    @MockBean private HeartRateAnomalyDetector detector;
    @MockBean private HeartRateLatestResultWriter latestResultWriter;
    @MockBean private HeartRateResultRepository heartRateResultRepository;
    @MockBean private DegradedEmergencyAlertService degradedEmergencyAlertService;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @Test
    @DisplayName("Redis 최신값 쓰기가 실패해도 단건 위급 심박을 저장하고 커밋 뒤 위급 알림 신호를 낸다")
    void Redis_최신값_쓰기가_실패해도_단건_위급_심박을_저장하고_위급_알림_신호를_낸다() {
        // given
        Long memberId = new TransactionTemplate(transactionManager).execute(status -> memberRepository.save(
                Member.createMember(MemberType.SENIOR, "시니어", "01000000000")).getId());
        given(detector.detect(eq(memberId), any(), anyString()))
                .willReturn(new DetectionResult(HeartRateStatus.EMERGENCY, true, "EMERGENCY", null));
        given(latestResultWriter.saveIfNewer(any())).willThrow(new RedisConnectionFailureException("redis down"));
        // 운영 기본값처럼 저하 경로를 켜 둔다. 수정 전 코드는 여기서 DB 장애로 오인해 저하 경로로 갔다.
        given(degradedEmergencyAlertService.enabled()).willReturn(true);

        // when
        HeartRateStatusResponse response = heartRateService.processHeartRate(
                memberId, HeartRateSingleRequest.of(185, MEASURED_AT, "서울시", "UNKNOWN"));

        // then
        assertThat(response.heartRateStatus()).isEqualTo(HeartRateStatus.EMERGENCY);
        assertThat(heartRateEventRepository.count()).isEqualTo(1);
        assertThat(heartRateEmergencyRepository.count()).isEqualTo(1);
        assertThat(emergencyEvents.received).containsExactly(new HeartRateEmergencyEvent(memberId, null));
        assertThat(meterRegistry.counter("heart.latest.write.failed").count()).isEqualTo(1.0);
        then(degradedEmergencyAlertService).should(never()).alertWithoutDatabase(anyLong(), any());
    }

    @TestConfiguration
    static class Config {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        EmergencyEvents emergencyEvents() {
            return new EmergencyEvents();
        }
    }

    /** 실제 위급 리스너처럼 커밋 뒤에만 받는다. 롤백된 발행은 여기에 오지 않는다. */
    static class EmergencyEvents {

        private final List<HeartRateEmergencyEvent> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void on(HeartRateEmergencyEvent event) {
            received.add(event);
        }
    }
}
