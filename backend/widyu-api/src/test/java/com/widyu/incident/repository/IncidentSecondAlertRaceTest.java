package com.widyu.incident.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.incident.GuardianResponseType;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentGuardianResponse;
import com.widyu.incident.IncidentKind;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IncidentSecondAlertRaceTest {
    @Autowired private IncidentRepository incidents;
    @Autowired private IncidentGuardianResponseRepository responses;
    @Autowired private PlatformTransactionManager transactions;
    @MockBean private JPAQueryFactory queryFactory;

    @AfterEach
    void cleanup() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            responses.deleteAll();
            incidents.deleteAll();
        });
    }

    @Test
    @DisplayName("2차 게이트와 멈춤이 경합하면 발송 또는 취소만 기록한다")
    void 이차_게이트와_멈춤이_경합하면_발송_또는_취소만_기록한다() throws Exception {
        // given
        long openedAtMs = 1_760_000_000_000L;
        long nowMs = openedAtMs + 181_000L;
        Long id = new TransactionTemplate(transactions).execute(status -> {
            Incident incident = Incident.builder().incidentRef("inc-race-739").memberId(1L)
                    .kind(IncidentKind.HR_ANOMALY).openedAtMs(openedAtMs)
                    .respondByMs(openedAtMs + 60_000L).build();
            incident.markChecking();
            incident.markInitialAlertSent(openedAtMs + 1_000L);
            return incidents.saveAndFlush(incident).getId();
        });
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> sent = workers.submit(() -> {
                ready.countDown();
                start.await();
                return new TransactionTemplate(transactions).execute(status -> {
                    incidents.findByIdForUpdate(id).orElseThrow();
                    return incidents.claimSecondAlertIfDue(id, nowMs);
                });
            });
            Future<Integer> cancelled = workers.submit(() -> {
                ready.countDown();
                start.await();
                return new TransactionTemplate(transactions).execute(status -> {
                    incidents.findByIdForUpdate(id).orElseThrow();
                    responses.saveAndFlush(IncidentGuardianResponse.of(id, 77L,
                            GuardianResponseType.ACKNOWLEDGED, nowMs));
                    return incidents.cancelSecondAlertIfPending(id, nowMs);
                });
            });

            // when
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int sentRows = sent.get(10, TimeUnit.SECONDS);
            int cancelledRows = cancelled.get(10, TimeUnit.SECONDS);

            // then
            Incident found = incidents.findById(id).orElseThrow();
            assertThat(sentRows + cancelledRows).isEqualTo(1);
            assertThat(found.getSecondAlertSentAtMs() != null)
                    .isNotEqualTo(found.getSecondAlertCancelledAtMs() != null);
            assertThat(responses.existsByIncidentId(id)).isTrue();
        } finally {
            workers.shutdownNow();
        }
    }
}
