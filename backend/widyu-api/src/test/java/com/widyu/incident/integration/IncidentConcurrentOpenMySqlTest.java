package com.widyu.incident.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.decision.DecisionRecord;
import com.widyu.decision.repository.DecisionRecordRepository;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.event.heart.dto.HeartRateEmergencyEvent;
import com.widyu.followup.application.FollowupCardService;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.global.properties.SensorProperties;
import com.widyu.heart.application.HeartRateEmergencyNotificationService;
import com.widyu.incident.application.IncidentEscalation;
import com.widyu.incident.application.IncidentRejectedAttemptRecorder;
import com.widyu.incident.application.IncidentRespondTransaction;
import com.widyu.incident.application.IncidentService;
import com.widyu.incident.repository.IncidentRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.application.FamilyAccessService;
import com.widyu.member.repository.MemberRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 같은 시니어의 위급 판정 두 건이 겹쳐도 사건이 하나만 열리는지 실제 MySQL(InnoDB REPEATABLE READ)에서 본다(#836, LLD-0103).
 *
 * <p>H2는 잠금 전 일반 SELECT가 고정하는 읽기 스냅샷을 재현하지 못해 MySQL에서만 의미가 있다. 순서는 래치로 강제한다.
 * 첫 리스너가 회원 행을 잠그고 사건을 저장한 뒤 본인확인 푸시(대역) 안에서 멈춘다. 둘째 리스너가 회원 행 잠금을
 * 기다리는 것을 {@code information_schema.innodb_trx}로 확인한 뒤 첫 리스너를 풀어 준다. 수정 전 리스너는 잠금 전에
 * 판정 기록을 읽어 스냅샷이 고정되므로, 잠금을 얻은 뒤에도 첫 사건을 못 보고 사건을 하나 더 연다.
 *
 * <p>실행: {@code INCIDENT_MYSQL_URL=jdbc:mysql://127.0.0.1:<port>/incident_<name> DB_USERNAME=root DB_PASSWORD=
 * ./gradlew :backend:widyu-api:test --tests 'com.widyu.incident.integration.IncidentConcurrentOpenMySqlTest'}.
 * {@code ddl-auto=create}라 일회용 DB만 지정한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.hibernate.ddl-auto=create"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaAuditingConfig.class, HeartRateEmergencyNotificationService.class, IncidentService.class,
        IncidentRespondTransaction.class})
@EnabledIfEnvironmentVariable(named = "INCIDENT_MYSQL_URL", matches = ".+")
@Tag("incident-mysql")
@DisplayName("동시 위급 판정의 사건 열기 MySQL 동시성")
class IncidentConcurrentOpenMySqlTest {

    private static final long WAIT_SEC = 30;

    @Autowired private HeartRateEmergencyNotificationService listener;
    @Autowired private MemberRepository members;
    @Autowired private DecisionRecordRepository decisions;
    @Autowired private IncidentRepository incidents;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @MockBean private FcmService fcmService;
    @MockBean private IncidentEscalation incidentEscalation;
    @MockBean private FcmOutboxService outboxService;
    @MockBean private FollowupCardService followupCardService;
    @MockBean private IncidentRejectedAttemptRecorder rejectedAttemptRecorder;
    @MockBean private FamilyAccessService familyAccessService;
    @MockBean private SensorProperties sensorProperties;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("INCIDENT_MYSQL_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/incident_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("루프백의 일회용 incident_ 데이터베이스만 허용합니다.");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> envOr("DB_USERNAME", "root"));
        registry.add("spring.datasource.password", () -> envOr("DB_PASSWORD", ""));
    }

    @Test
    @DisplayName("같은 시니어의 위급 판정 두 건이 겹치면 사건 하나에 모이고 본인확인 푸시는 한 번만 나간다")
    void 같은_시니어의_위급_판정_두_건이_겹치면_사건_하나에_모이고_본인확인_푸시는_한_번만_나간다() throws Exception {
        // given
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Long seniorId = transactions.execute(status -> members.save(
                Member.createMember(MemberType.SENIOR, "시니어", "01000000836")).getId());
        transactions.executeWithoutResult(status -> {
            decisions.save(alert("dec-836-a", seniorId));
            decisions.save(alert("dec-836-b", seniorId));
        });
        given(sensorProperties.incident()).willReturn(new SensorProperties.Incident(60, 5000, true, 5, 90));
        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger selfCheckPushes = new AtomicInteger();
        willAnswer(invocation -> {
            if (selfCheckPushes.getAndIncrement() == 0) {
                firstHoldsLock.countDown();
                releaseFirst.await(WAIT_SEC, TimeUnit.SECONDS);
            }
            return null;
        }).given(fcmService).sendMessageToUser(eq(seniorId), any());
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // when
        try {
            Future<?> first = pool.submit(() ->
                    listener.handleHeartRateEmergency(new HeartRateEmergencyEvent(seniorId, "dec-836-a")));
            assertThat(firstHoldsLock.await(WAIT_SEC, TimeUnit.SECONDS)).isTrue();
            Future<?> second = pool.submit(() ->
                    listener.handleHeartRateEmergency(new HeartRateEmergencyEvent(seniorId, "dec-836-b")));
            awaitLockWait();
            releaseFirst.countDown();
            first.get(WAIT_SEC, TimeUnit.SECONDS);
            second.get(WAIT_SEC, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }

        // then
        long opened = incidents.findAll().stream().filter(row -> row.getMemberId().equals(seniorId)).count();
        System.out.println("[MEASURE] scenario=concurrent_alert_open incidents=" + opened
                + " selfCheckPushes=" + selfCheckPushes.get());
        assertThat(opened).isEqualTo(1);
        assertThat(selfCheckPushes.get()).isEqualTo(1);
    }

    /** 둘째 트랜잭션이 회원 행 잠금을 기다릴 때까지 기다린다. 수정 전·후 모두 이 잠금에서 멈춘다. */
    private void awaitLockWait() throws InterruptedException {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(WAIT_SEC);
        while (System.currentTimeMillis() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state = 'LOCK WAIT'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("둘째 트랜잭션이 회원 행 잠금을 기다리지 않았습니다.");
    }

    private static DecisionRecord alert(String decisionId, Long memberId) {
        return DecisionRecord.builder()
                .decisionId(decisionId)
                .memberId(memberId)
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
                .build();
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null) {
            return fallback;
        }
        return value;
    }
}
