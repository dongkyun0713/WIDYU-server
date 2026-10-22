package com.widyu.heart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;

import com.widyu.decision.application.DecisionRecordPersistenceService;
import com.widyu.decision.repository.DecisionRecordRepository;
import com.widyu.fcm.application.degraded.DegradedEmergencyAlertService;
import com.widyu.global.properties.SensorProperties;
import com.widyu.heart.HeartRateResult;
import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.application.HeartRateAnomalyDetector.DetectionResult;
import com.widyu.heart.dto.request.HeartRateMeasurement;
import com.widyu.heart.dto.request.HeartRateSingleRequest;
import com.widyu.heart.repository.HeartRateEmergencyRepository;
import com.widyu.heart.repository.HeartRateEventRepository;
import com.widyu.heart.repository.HeartRateRedisFixture;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import com.widyu.sensor.dto.request.HeartRateBatchRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 늦게 처리된 심박이 Redis 최신값을 과거로 되돌리는지 실제 Redis에서 센다(LLD-0090 AC3·AC4·AC5).
 *
 * <p>{@code HEART_REDIS_PORT}로 지정한 버리는 Redis(127.0.0.1)에만 붙는다. 변수가 없으면 건너뛰고, 6379면
 * 개발용 Redis를 덮을 수 있어 실행을 거부한다. Redis 연결·템플릿은 운영 설정({@link RedisConfig})으로 만들고,
 * 리포지토리는 Spring Data Redis 리포지토리 팩토리로 만든다. AI 판정기와 MySQL 리포지토리만 대역이다.
 *
 * <p>순서는 대역 AI 판정기의 래치로 강제한다. 먼저 받은 측정(t1)의 판정을 붙잡아 두고, 나중 측정(t2)이
 * 최신값까지 다 쓴 뒤에 t1을 풀어 준다. 회귀 건수는 최종 최신값이 t2가 아닌 반복 수다.
 */
@EnabledIfEnvironmentVariable(named = "HEART_REDIS_PORT", matches = "\\d+")
@DisplayName("심박 최신값 순서 실측 (실제 Redis)")
class HeartRateLatestResultOrderingRedisTest {

    private static final int REPEAT = 100;
    private static final int CONCURRENT_WRITERS = 100;
    private static final int CONCURRENT_REPEAT = 20;
    private static final long MEMBER_BASE = 780_000_000L;
    private static final ZoneId HEART_RATE_ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 19, 9, 0);
    private static final DetectionResult NORMAL = new DetectionResult(HeartRateStatus.NORMAL, false, "NORMAL", null);

    @Test
    @DisplayName("단건 t1 처리가 t2 저장 뒤에 끝나면 최신값은 t2로 남는다")
    void 단건_t1_처리가_t2_저장_뒤에_끝나면_최신값은_t2로_남는다() throws Exception {
        try (HeartRateRedisFixture redis = HeartRateRedisFixture.open()) {
            // given
            HeldDetector detector = new HeldDetector();
            HeartRateService service = singleService(redis, detector);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            int regressions = 0;

            // when
            for (int r = 0; r < REPEAT; r++) {
                Long memberId = MEMBER_BASE + 1_000 + r;
                LocalDateTime t1 = BASE.plusSeconds(r * 10L);
                LocalDateTime t2 = t1.plusSeconds(1);
                CountDownLatch release = detector.hold(t1);
                Future<?> older = executor.submit(() -> service.processHeartRate(memberId, request(t1)));
                detector.awaitEntered(t1);
                service.processHeartRate(memberId, request(t2));
                release.countDown();
                older.get(10, TimeUnit.SECONDS);
                if (!t2.equals(latestMeasuredAt(redis, memberId))) {
                    regressions++;
                }
                redis.deleteMember(memberId);
            }
            executor.shutdownNow();

            // then
            System.out.println("[MEASURE] S1 single R=" + REPEAT + " regressions=" + regressions);
            assertThat(regressions).isZero();
        }
    }

    @Test
    @DisplayName("오래된 배치가 새 배치 저장 뒤에 끝나면 최신값은 새 배치의 마지막 샘플로 남는다")
    void 오래된_배치가_새_배치_저장_뒤에_끝나면_최신값은_새_배치의_마지막_샘플로_남는다() throws Exception {
        try (HeartRateRedisFixture redis = HeartRateRedisFixture.open()) {
            // given
            HeldDetector detector = new HeldDetector();
            HeartRateBatchService service = batchService(redis, detector);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            int regressions = 0;

            // when
            for (int r = 0; r < REPEAT; r++) {
                Member member = member(MEMBER_BASE + 2_000 + r);
                long olderStartMs = BASE.atZone(HEART_RATE_ZONE).toInstant().toEpochMilli() + r * 60_000L;
                long newerStartMs = olderStartMs + 3_000L;
                List<HeartRateBatchRequest.Sample> olderBatch = samples(olderStartMs);
                List<HeartRateBatchRequest.Sample> newerBatch = samples(newerStartMs);
                LocalDateTime expected = toMeasuredAt(newerStartMs + 2_000L);
                String olderBatchId = "older-" + r;
                CountDownLatch release = detector.hold(toMeasuredAt(olderStartMs));
                Future<?> older = executor.submit(
                        () -> service.storeAndAssess(member, olderBatchId, null, olderBatch, olderStartMs));
                detector.awaitEntered(toMeasuredAt(olderStartMs));
                service.storeAndAssess(member, "newer-" + r, null, newerBatch, newerStartMs);
                release.countDown();
                older.get(10, TimeUnit.SECONDS);
                if (!expected.equals(latestMeasuredAt(redis, member.getId()))) {
                    regressions++;
                }
                redis.deleteMember(member.getId());
            }
            executor.shutdownNow();

            // then
            System.out.println("[MEASURE] S2 batch R=" + REPEAT + " regressions=" + regressions);
            assertThat(regressions).isZero();
        }
    }

    @Test
    @DisplayName("한 회원에 서로 다른 측정 시각을 동시에 쓰면 최신값은 가장 늦은 측정으로 남는다")
    void 한_회원에_서로_다른_측정_시각을_동시에_쓰면_최신값은_가장_늦은_측정으로_남는다() throws Exception {
        try (HeartRateRedisFixture redis = HeartRateRedisFixture.open()) {
            // given
            HeartRatePersistenceService persistence = persistenceService(redis, mock(MemberRepository.class));
            ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_WRITERS);
            int wrongFinals = 0;
            List<String> finalRanks = new ArrayList<>();

            // when
            for (int r = 0; r < CONCURRENT_REPEAT; r++) {
                Long memberId = MEMBER_BASE + 3_000 + r;
                List<LocalDateTime> times = new ArrayList<>();
                for (int i = 0; i < CONCURRENT_WRITERS; i++) {
                    times.add(BASE.plusSeconds(i));
                }
                Collections.shuffle(times);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> writes = new ArrayList<>();
                for (LocalDateTime measuredAt : times) {
                    writes.add(executor.submit(() -> {
                        start.await();
                        persistence.updateLatestResult(memberId, HeartRateStatus.NORMAL, 70, measuredAt);
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> write : writes) {
                    write.get(30, TimeUnit.SECONDS);
                }
                LocalDateTime latest = latestMeasuredAt(redis, memberId);
                LocalDateTime max = BASE.plusSeconds(CONCURRENT_WRITERS - 1);
                if (!max.equals(latest)) {
                    wrongFinals++;
                }
                finalRanks.add(String.valueOf(Duration.between(BASE, latest).getSeconds()));
                redis.deleteMember(memberId);
            }
            executor.shutdownNow();

            // then
            System.out.println("[MEASURE] S3 concurrent N=" + CONCURRENT_WRITERS + " R=" + CONCURRENT_REPEAT
                    + " wrongFinals=" + wrongFinals + " finalSecondOffsets(max=" + (CONCURRENT_WRITERS - 1) + ")="
                    + finalRanks);
            assertThat(wrongFinals).isZero();
        }
    }

    private static HeartRateService singleService(HeartRateRedisFixture redis, HeldDetector detector) {
        MemberRepository memberRepository = mock(MemberRepository.class);
        given(memberRepository.findById(anyLong()))
                .willAnswer(invocation -> Optional.of(member(invocation.getArgument(0))));
        HeartRateEventRepository eventRepository = mock(HeartRateEventRepository.class);
        given(eventRepository.existsByMemberIdAndMeasuredAt(anyLong(), any())).willReturn(false);
        return new HeartRateService(
                detector.asDetector(),
                persistenceService(redis, memberRepository),
                redis.repository(),
                eventRepository,
                mock(HeartRateEmergencyRepository.class),
                memberRepository,
                mock(DegradedEmergencyAlertService.class));
    }

    private static HeartRateBatchService batchService(HeartRateRedisFixture redis, HeldDetector detector) {
        HeartRateEventRepository eventRepository = mock(HeartRateEventRepository.class);
        given(eventRepository.existsByMemberIdAndMeasuredAt(anyLong(), any())).willReturn(false);
        return new HeartRateBatchService(
                detector.asDetector(),
                persistenceService(redis, mock(MemberRepository.class)),
                eventRepository,
                mock(DecisionRecordPersistenceService.class),
                mock(SensorProperties.class),
                new SimpleMeterRegistry());
    }

    /** 최신값 쓰기만 실제 Redis로 가고 MySQL 저장은 대역이다. */
    private static HeartRatePersistenceService persistenceService(
            HeartRateRedisFixture redis, MemberRepository memberRepository) {
        return new HeartRatePersistenceService(
                redis.writer(),
                mock(HeartRateEventRepository.class),
                mock(HeartRateEmergencyRepository.class),
                memberRepository,
                mock(DecisionRecordRepository.class),
                mock(ApplicationEventPublisher.class),
                new SimpleMeterRegistry());
    }

    private static LocalDateTime latestMeasuredAt(HeartRateRedisFixture redis, Long memberId) {
        return redis.repository().findByMemberId(memberId).map(HeartRateResult::getMeasuredAt).orElse(null);
    }

    private static HeartRateSingleRequest request(LocalDateTime measuredAt) {
        return HeartRateSingleRequest.of(72, measuredAt, null, "UNKNOWN");
    }

    private static List<HeartRateBatchRequest.Sample> samples(long startMs) {
        return List.of(
                HeartRateBatchRequest.Sample.of(70, startMs, "HIGH"),
                HeartRateBatchRequest.Sample.of(71, startMs + 1_000L, "HIGH"),
                HeartRateBatchRequest.Sample.of(72, startMs + 2_000L, "HIGH"));
    }

    private static LocalDateTime toMeasuredAt(long tsMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(tsMs), HEART_RATE_ZONE);
    }

    private static Member member(Long id) {
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01000000000");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    /** 지정한 측정 시각의 판정을 래치가 풀릴 때까지 붙잡는 AI 판정기 대역. */
    private static final class HeldDetector {

        private final Map<LocalDateTime, CountDownLatch> releases = new ConcurrentHashMap<>();
        private final Map<LocalDateTime, CountDownLatch> entered = new ConcurrentHashMap<>();

        CountDownLatch hold(LocalDateTime measuredAt) {
            CountDownLatch release = new CountDownLatch(1);
            releases.put(measuredAt, release);
            entered.put(measuredAt, new CountDownLatch(1));
            return release;
        }

        void awaitEntered(LocalDateTime measuredAt) throws InterruptedException {
            assertThat(entered.get(measuredAt).await(10, TimeUnit.SECONDS)).isTrue();
        }

        HeartRateAnomalyDetector asDetector() {
            HeartRateAnomalyDetector detector = mock(HeartRateAnomalyDetector.class);
            willAnswer(invocation -> {
                HeartRateMeasurement measurement = invocation.getArgument(1);
                CountDownLatch release = releases.remove(measurement.measuredAt());
                if (release != null) {
                    entered.get(measurement.measuredAt()).countDown();
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                }
                return NORMAL;
            }).given(detector).detect(anyLong(), any(HeartRateMeasurement.class), anyString());
            return detector;
        }
    }
}
