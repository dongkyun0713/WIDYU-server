package com.widyu.fcm.application.degraded;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.widyu.auth.repository.RefreshTokenRepository;
import com.widyu.auth.repository.TemporaryMemberRepository;
import com.widyu.fcm.FcmNotification;
import com.widyu.fcm.FcmOutbox;
import com.widyu.fcm.MemberFcmToken;
import com.widyu.fcm.application.FcmDelivery;
import com.widyu.fcm.application.FcmTransport;
import com.widyu.fcm.repository.FcmNotificationRepository;
import com.widyu.fcm.repository.FcmOutboxRepository;
import com.widyu.fcm.repository.MemberFcmTokenRepository;
import com.widyu.global.util.MemberUtil;
import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.application.HeartRateAnomalyDetector;
import com.widyu.heart.application.HeartRateAnomalyDetector.DetectionResult;
import com.widyu.heart.application.HeartRateService;
import com.widyu.heart.dto.request.HeartRateSingleRequest;
import com.widyu.member.Family;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.FamilyRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * DB 장애 주입 측정(LLD-0087 5.4). DataSource를 막아 RDS 무응답을 흉내 내고, 실제 Redis로 저하 경로 적재·소비·보정을 돌린다.
 * FCM 전송과 AI 판정은 mock이라 지연은 앱 내부 경로(심박 수신 → Redis → 전송 호출)만 잰다.
 *
 * <p>실행: {@code DEGRADED_REDIS=1 [DEGRADED_REDIS_PORT=6379] [DEGRADED_N=20] ./gradlew :backend:widyu-api:test
 * --tests 'com.widyu.fcm.application.degraded.DegradedAlertOutageMeasurementTest'}. 결과는 {@code MEASUREMENT} 줄로 출력한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // 다른 통합 테스트 컨텍스트의 H2(testdb)를 건드리지 않게 이 측정만의 DB를 쓴다.
        "spring.datasource.url=jdbc:h2:mem:degraded_outage;DB_CLOSE_DELAY=-1",
        "firebase.config-path=/dev/null",
        "s3.credentials.access-key=test",
        "s3.credentials.secret-key=test",
        "s3.region.statics=ap-northeast-2",
        "s3.bucket-name=test-bucket",
        "coolsms.api-key=test",
        "coolsms.api-secret=test",
        "coolsms.api-url=https://api.coolsms.co.kr",
        "coolsms.from-phone-number=01000000000",
        "coolsms.verification-code-length=6",
        "coolsms.verification-code-ttl=300",
        "coolsms.message-template=인증번호: {code}"
})
@EnabledIfEnvironmentVariable(named = "DEGRADED_REDIS", matches = ".+")
@Tag("degraded-redis")
@DisplayName("DB 장애 주입 저하 경로 측정")
class DegradedAlertOutageMeasurementTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final int GUARDIANS_PER_SENIOR = 2;

    @MockBean private FcmTransport transport;
    @MockBean private HeartRateAnomalyDetector detector;
    @MockBean private MemberUtil memberUtil;
    @MockBean private TemporaryMemberRepository temporaryMemberRepository;
    @MockBean private RefreshTokenRepository refreshTokenRepository;
    @MockBean private S3Client s3Client;
    @MockBean private net.bramp.ffmpeg.FFmpeg ffmpeg;
    @MockBean private net.bramp.ffmpeg.FFprobe ffprobe;

    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private StringRedisTemplate redis;
    @Autowired private HeartRateService heartRateService;
    @Autowired private EmergencyRecipientSnapshotService snapshots;
    @Autowired private DegradedEmergencyAlertConsumer consumer;
    @Autowired private DegradedAlertReconciler reconciler;
    @Autowired private FcmNotificationRepository notifications;
    @Autowired private FcmOutboxRepository outbox;
    @Autowired private MemberRepository members;
    @Autowired private FamilyRepository families;
    @Autowired private SeniorProfileRepository seniors;
    @Autowired private FamilyMembershipRepository memberships;
    @Autowired private MemberFcmTokenRepository tokens;
    @Autowired private MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void redisPort(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.port", DegradedAlertOutageMeasurementTest::redisPortSetting);
    }

    @Test
    @DisplayName("DB가 끊긴 동안 위급 심박은 전 보호자에게 한 번씩 가고, 복구 뒤 보정이 알림 이력을 한 번만 남긴다")
    void DB가_끊긴_동안_위급은_보호자에게_가고_복구뒤_이력이_한번만_남는다() throws Exception {
        // given
        assumeRedis();
        int n = seniorCount();
        resetRedis();
        List<Long> seniorIds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            seniorIds.add(seedFamily());
        }
        snapshots.refreshAll();
        Map<Long, Long> startedAt = new ConcurrentHashMap<>();
        List<long[]> sends = recordSends();
        givenEverySampleIsEmergency();
        long historyBefore = degradedHistory().size();

        // when: DB 차단 상태로 시니어마다 위급 1건 → 소비자 1회
        ToggleableDataSource outage = (ToggleableDataSource) dataSource;
        outage.down = true;
        try {
            LocalDateTime measuredAt = LocalDateTime.now().withNano(0);
            for (Long seniorId : seniorIds) {
                startedAt.put(seniorId, System.nanoTime());
                heartRateService.processHeartRate(seniorId, sample(measuredAt));
                consumer.poll();
            }
        } finally {
            outage.down = false;
        }

        // then: 전송·pending·보정 목록
        long pending = redis.opsForStream().pending(DegradedEmergencyAlertService.STREAM_KEY,
                DegradedEmergencyAlertConsumer.GROUP, Range.unbounded(), 1000).size();
        Long queued = redis.opsForList().size(DegradedEmergencyAlertConsumer.RECONCILE_KEY);
        List<Long> latencies = new ArrayList<>();
        for (long[] send : sends) {
            latencies.add((send[1] - startedAt.get(send[0])) / 1_000);
        }
        Collections.sort(latencies);
        System.out.println("MEASUREMENT scenario=A n=" + n + " guardiansPerSenior=" + GUARDIANS_PER_SENIOR
                + " redisPort=" + redisPortSetting() + " sendAttempts=" + sends.size()
                + " pending=" + pending + " reconcileQueued=" + queued);
        System.out.println("MEASUREMENT scenario=A latency_us(processHeartRate->transport.send, poll direct)"
                + " samples=" + latencies.size() + " p50=" + percentile(latencies, 50)
                + " p95=" + percentile(latencies, 95) + " max=" + latencies.get(latencies.size() - 1));
        assertThat(sends).hasSize(n * GUARDIANS_PER_SENIOR);
        assertThat(pending).isZero();
        assertThat(queued).isEqualTo((long) n * GUARDIANS_PER_SENIOR);

        // when: DB 복구 → 보정
        List<String> queuedRecords = redis.opsForList().range(DegradedEmergencyAlertConsumer.RECONCILE_KEY, 0, -1);
        int reconcileCalls = 0;
        while (redis.opsForList().size(DegradedEmergencyAlertConsumer.RECONCILE_KEY) > 0 && reconcileCalls < 10) {
            reconciler.reconcile();
            reconcileCalls++;
        }
        List<FcmNotification> history = degradedHistory();
        redis.opsForList().rightPushAll(DegradedEmergencyAlertConsumer.RECONCILE_KEY, queuedRecords);
        reconciler.reconcile();
        int historyAfterReplay = degradedHistory().size();
        long outboxRows = outbox.count();

        // then: 이력 N×2, 멱등, outbox 없음
        System.out.println("MEASUREMENT scenario=A reconcile historyBefore=" + historyBefore
                + " historyAfter=" + history.size() + " historyAfterReplay=" + historyAfterReplay
                + " reconcileCalls=" + reconcileCalls + " maxDecisionIdLength="
                + history.stream().mapToInt(row -> row.getDecisionId().length()).max().orElse(0)
                + " outbox=" + outboxRows);
        assertThat(history).hasSize(n * GUARDIANS_PER_SENIOR)
                .allSatisfy(row -> assertThat(row.getDecisionId()).startsWith("degraded:").hasSizeLessThanOrEqualTo(40));
        assertThat(historyAfterReplay).isEqualTo(n * GUARDIANS_PER_SENIOR);
        assertThat(outboxRows).isZero();

        // when: DB 정상 상태에서 같은 시니어의 위급을 다시 받으면 정상 경로 outbox 행이 생긴다
        int sendsBefore = sends.size();
        double dedupedBefore = meterRegistry.counter("fcm.degraded.deduped").count();
        Long markedSenior = seniorIds.getFirst();
        heartRateService.processHeartRate(markedSenior, sample(LocalDateTime.now().withNano(0).plusSeconds(1)));
        FcmOutbox row = awaitFinishedOutbox(markedSenior);

        // then: 표식 때문에 claim에서 취소되고 전송은 늘지 않는다
        System.out.println("MEASUREMENT scenario=A marker outboxRows=" + outbox.count() + " outboxState="
                + stateOf(row) + " sendAttemptsBefore=" + sendsBefore + " sendAttemptsAfter=" + sends.size()
                + " deduped=" + (meterRegistry.counter("fcm.degraded.deduped").count() - dedupedBefore));
        assertThat(row).isNotNull();
        assertThat(row.getState()).isEqualTo(FcmOutbox.State.CANCELLED);
        assertThat(sends).hasSize(sendsBefore);
    }

    @Test
    @DisplayName("DB가 끊긴 동안 같은 시니어의 위급이 10초 안에 5번 와도 보호자마다 한 번만 보낸다")
    void DB가_끊긴_동안_같은_시니어의_위급이_반복돼도_보호자마다_한번만_보낸다() throws Exception {
        // given
        assumeRedis();
        resetRedis();
        Long seniorId = seedFamily();
        snapshots.refreshAll();
        List<long[]> sends = recordSends();
        givenEverySampleIsEmergency();

        // when
        ToggleableDataSource outage = (ToggleableDataSource) dataSource;
        outage.down = true;
        long started = System.nanoTime();
        try {
            LocalDateTime first = LocalDateTime.now().withNano(0);
            for (int i = 0; i < 5; i++) {
                heartRateService.processHeartRate(seniorId, sample(first.plusSeconds(i * 2L)));
                consumer.poll();
            }
        } finally {
            outage.down = false;
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        // then
        System.out.println("MEASUREMENT scenario=B samples=5 sampleSpanSec=8 elapsedMs=" + elapsedMs
                + " guardians=" + GUARDIANS_PER_SENIOR + " sendAttempts=" + sends.size()
                + " throttled=" + meterRegistry.counter("fcm.degraded.skipped", "reason", "throttled").count());
        assertThat(sends).hasSize(GUARDIANS_PER_SENIOR);
    }

    private void assumeRedis() {
        boolean reachable;
        try {
            reachable = "PONG".equalsIgnoreCase(redis.getRequiredConnectionFactory().getConnection().ping());
        } catch (RuntimeException exception) {
            reachable = false;
        }
        assumeTrue(reachable, "로컬 Redis(localhost:" + redisPortSetting() + ")가 응답하지 않아 측정을 건너뜁니다.");
    }

    /** 이 측정이 쓰는 저하 경로 키를 비우고 소비자 그룹을 다시 만든다(소비자 빈은 그룹이 있다고 기억한다). */
    private void resetRedis() {
        Set<String> keys = redis.keys("fcm:degraded:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        redis.opsForStream().createGroup(DegradedEmergencyAlertService.STREAM_KEY, ReadOffset.from("0"),
                DegradedEmergencyAlertConsumer.GROUP);
    }

    /** 시니어 1명, 방장 포함 보호자 2명, 보호자마다 활성 토큰 1개. 시니어 회원 ID를 돌려준다. */
    private Long seedFamily() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            int seq = SEQ.incrementAndGet();
            Family family = families.save(Family.createFamily(String.format("D%05d", seq)));
            Member senior = members.save(Member.createMember(MemberType.SENIOR, "시니어" + seq, phone(seq, 0)));
            seniors.save(SeniorProfile.createSeniorProfile(senior, family, "서울시", String.format("I%06d", seq),
                    LocalDate.of(1950, 1, 1)));
            for (int g = 1; g <= GUARDIANS_PER_SENIOR; g++) {
                Member guardian = members.save(Member.createMember(MemberType.GUARDIAN, "보호자", phone(seq, g)));
                if (g == 1) {
                    memberships.save(FamilyMembership.createLeaderMembership(family, guardian));
                } else {
                    memberships.save(FamilyMembership.createMembership(family, guardian));
                }
                tokens.save(MemberFcmToken.builder().member(guardian)
                        .token("synthetic-" + seq + "-" + g).active(true).build());
            }
            return senior.getId();
        });
    }

    /** 전송 호출마다 (시니어 ID, nanoTime)을 남기고 성공을 돌려준다. */
    private List<long[]> recordSends() {
        List<long[]> sends = Collections.synchronizedList(new ArrayList<>());
        given(transport.send(any(FcmDelivery.class), any())).willAnswer(invocation -> {
            FcmDelivery delivery = invocation.getArgument(0);
            sends.add(new long[] {delivery.message().seniorId(), System.nanoTime()});
            return FcmTransport.Result.delivered();
        });
        return sends;
    }

    private void givenEverySampleIsEmergency() {
        given(detector.detect(any(), any(), any()))
                .willReturn(new DetectionResult(HeartRateStatus.EMERGENCY, true, "EMERGENCY", null));
    }

    private List<FcmNotification> degradedHistory() {
        return notifications.findAll().stream()
                .filter(row -> row.getDecisionId() != null && row.getDecisionId().startsWith("degraded:"))
                .collect(Collectors.toList());
    }

    private FcmOutbox awaitFinishedOutbox(Long seniorId) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            FcmOutbox row = outbox.findAll().stream()
                    .filter(candidate -> seniorId.equals(candidate.getRelatedMemberId()))
                    .findFirst().orElse(null);
            if (row != null && row.getState() != FcmOutbox.State.PENDING
                    && row.getState() != FcmOutbox.State.CLAIMED) {
                return row;
            }
            Thread.sleep(100);
        }
        return outbox.findAll().stream()
                .filter(candidate -> seniorId.equals(candidate.getRelatedMemberId()))
                .findFirst().orElse(null);
    }

    private static String stateOf(FcmOutbox row) {
        if (row == null) {
            return "NONE";
        }
        return row.getState().name();
    }

    private static HeartRateSingleRequest sample(LocalDateTime measuredAt) {
        return HeartRateSingleRequest.of(187, measuredAt, "서울시", "ACTIVE");
    }

    /** nearest-rank 백분위. */
    private static long percentile(List<Long> sorted, int percent) {
        int rank = (int) Math.ceil(percent / 100.0 * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1);
    }

    private static String phone(int seq, int slot) {
        return String.format("010%07d%d", seq, slot);
    }

    private static int seniorCount() {
        String value = System.getenv("DEGRADED_N");
        if (value == null || value.isBlank()) {
            return 20;
        }
        return Integer.parseInt(value);
    }

    private static String redisPortSetting() {
        String value = System.getenv("DEGRADED_REDIS_PORT");
        if (value == null || value.isBlank()) {
            return "6379";
        }
        return value;
    }

    /** down이면 새 연결을 주지 않는다. RDS 무응답 뒤 커넥션 풀이 연결을 못 얻는 상황을 흉내 낸다. */
    static class ToggleableDataSource extends DelegatingDataSource {

        volatile boolean down;

        ToggleableDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (down) {
                throw new SQLTransientConnectionException("simulated outage");
            }
            return super.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            if (down) {
                throw new SQLTransientConnectionException("simulated outage");
            }
            return super.getConnection(username, password);
        }
    }

    @TestConfiguration
    static class OutageConfig {

        @Bean
        static BeanPostProcessor toggleableDataSourcePostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource source && !(bean instanceof ToggleableDataSource)) {
                        return new ToggleableDataSource(source);
                    }
                    return bean;
                }
            };
        }
    }
}
