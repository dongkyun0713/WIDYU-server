package com.widyu.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

import com.widyu.album.application.AlbumFileService;
import com.widyu.album.application.AlbumVideoProcessingService;
import com.widyu.album.application.AlbumVideoProcessingService.VideoEntry;
import com.widyu.album.repository.AlbumRepository;
import com.widyu.auth.application.SmsService;
import com.widyu.fcm.application.FcmTransport;
import com.widyu.global.infrastructure.s3.S3DirectUploadService;
import com.widyu.goal.healthschedule.application.HealthScheduleProgressService;
import com.widyu.location.realtime.event.SeniorLocationUpdatedEvent;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 실제 스프링 컨텍스트의 {@code @Async} 실행기로 영상 처리·위치 이벤트가 몇 개의 스레드에서 동시에 도는지 잰다(LLD-0092).
 * FFmpeg·S3·일정 판정은 일정 시간 붙잡는 대역으로 바꾸고, 트랜잭션과 H2 커넥션 풀(Hikari 기본 10)은 실제 것을 쓴다.
 *
 * <p>측정 실행: {@code ASYNC_MEASURE=1 ./gradlew :backend:widyu-api:test
 * --tests 'com.widyu.global.config.AsyncExecutorIntegrationTest'}. 결과는 {@code MEASUREMENT} 줄로 출력한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // 다른 통합 테스트 컨텍스트의 H2(testdb)를 건드리지 않게 이 테스트만의 DB를 쓴다.
        "spring.datasource.url=jdbc:h2:mem:async_executor;DB_CLOSE_DELAY=-1",
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
@DisplayName("@Async 실행기 통합 테스트")
class AsyncExecutorIntegrationTest {

    private static final int VIDEO_REQUESTS = 20;
    private static final long VIDEO_HOLD_MS = 300;
    private static final int LOCATION_EVENTS = 1_000;
    private static final long LOCATION_HOLD_MS = 20;
    private static final String CALLER_PREFIX = "measure-caller-";

    @MockBean private FcmTransport fcmTransport;
    @MockBean private com.widyu.auth.repository.TemporaryMemberRepository temporaryMemberRepository;
    @MockBean private com.widyu.auth.repository.RefreshTokenRepository refreshTokenRepository;
    @MockBean private com.widyu.auth.infrastructure.AuthLimitStore authLimitStore;
    @MockBean private com.widyu.auth.infrastructure.ClientIpResolver clientIpResolver;
    @MockBean private S3Client s3Client;
    @MockBean private SmsService smsService;
    @MockBean private net.bramp.ffmpeg.FFmpeg ffmpeg;
    @MockBean private net.bramp.ffmpeg.FFprobe ffprobe;
    @MockBean private AlbumFileService albumFileService;
    @MockBean private S3DirectUploadService s3DirectUploadService;
    @MockBean private AlbumRepository albumRepository;
    @MockBean private HealthScheduleProgressService healthScheduleProgressService;

    @Autowired private AlbumVideoProcessingService albumVideoProcessingService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private DataSource dataSource;
    @Autowired private MeterRegistry meterRegistry;

    @Test
    @DisplayName("영상 처리 @Async를 호출하면 SimpleAsyncTaskExecutor가 아닌 영상 실행기 스레드에서 실행한다")
    void 영상_처리는_영상_실행기_스레드에서_실행한다() throws Exception {
        // given
        Probe probe = new Probe();
        given(albumRepository.findById(anyLong())).willReturn(Optional.empty());
        given(albumFileService.uploadAlbumVideoWithThumbnail(any(MultipartFile.class), anyLong()))
                .willAnswer(invocation -> {
                    probe.hold(0);
                    return new AlbumFileService.VideoUploadResult("v", "t", 1);
                });
        File temp = Files.createTempFile("async-ac1-", ".mp4").toFile();

        // when
        albumVideoProcessingService.processVideosAsync(1L, 1L, List.of(new VideoEntry(0, temp, "a.mp4", "video/mp4")));

        // then
        assertThat(waitUntil(() -> probe.completed.get() == 1, 10_000)).isTrue();
        assertThat(probe.threads).singleElement().asString().startsWith("album-video-");
    }

    @Test
    @DisplayName("위치 갱신 이벤트를 발행하면 SimpleAsyncTaskExecutor가 아닌 기본 실행기 스레드에서 처리한다")
    void 위치_이벤트는_기본_실행기_스레드에서_처리한다() throws Exception {
        // given
        Probe probe = new Probe();
        willAnswer(invocation -> {
            probe.hold(0);
            return null;
        }).given(healthScheduleProgressService).completeArrivedSchedules(anyLong(), anyDouble(), anyDouble());

        // when
        eventPublisher.publishEvent(new SeniorLocationUpdatedEvent(1L, 37.5, 127.0));

        // then
        assertThat(waitUntil(() -> probe.completed.get() == 1, 10_000)).isTrue();
        assertThat(probe.threads).singleElement().asString().startsWith("async-");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ASYNC_MEASURE", matches = ".+")
    @DisplayName("영상 처리 요청 20건을 동시에 보내면 동시에 실행된 처리 수와 실행 스레드를 기록한다")
    void 영상_처리_동시_실행_수를_측정한다() throws Exception {
        // given
        Probe probe = new Probe();
        given(albumRepository.findById(anyLong())).willReturn(Optional.empty());
        given(albumFileService.uploadAlbumVideoWithThumbnail(any(MultipartFile.class), anyLong()))
                .willAnswer(invocation -> {
                    probe.hold(VIDEO_HOLD_MS);
                    return new AlbumFileService.VideoUploadResult("v", "t", 1);
                });
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ConnectionSampler connections = ConnectionSampler.start(dataSource);
        long started = System.nanoTime();

        // when
        ExecutorService callers = Executors.newFixedThreadPool(VIDEO_REQUESTS, named(CALLER_PREFIX));
        CountDownLatch gate = new CountDownLatch(1);
        for (int i = 0; i < VIDEO_REQUESTS; i++) {
            long albumId = i + 1;
            callers.submit(() -> {
                File temp = Files.createTempFile("async-measure-", ".mp4").toFile();
                gate.await();
                try {
                    albumVideoProcessingService.processVideosAsync(albumId, 1L,
                            List.of(new VideoEntry(0, temp, "a.mp4", "video/mp4")));
                    accepted.incrementAndGet();
                } catch (RuntimeException e) {
                    rejected.incrementAndGet();
                    Files.deleteIfExists(temp.toPath());
                }
                return null;
            });
        }
        gate.countDown();
        callers.shutdown();
        callers.awaitTermination(30, TimeUnit.SECONDS);
        boolean drained = waitUntil(() -> probe.completed.get() == accepted.get(), 120_000);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        int peakConnections = connections.stop();

        // then
        System.out.printf("MEASUREMENT video requests=%d holdMs=%d accepted=%d rejected=%d completed=%d drained=%s "
                        + "maxConcurrent=%d distinctThreads=%d callerThreadRuns=%d peakActiveDbConnections=%d "
                        + "elapsedMs=%d threadPrefixes=%s%n",
                VIDEO_REQUESTS, VIDEO_HOLD_MS, accepted.get(), rejected.get(), probe.completed.get(), drained,
                probe.maxConcurrent.get(), probe.threads.size(), probe.callerRuns.get(), peakConnections,
                elapsedMs, probe.prefixes());
        assertThat(drained).isTrue();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ASYNC_MEASURE", matches = ".+")
    @DisplayName("위치 이벤트 1000건을 빠르게 발행하면 처리 스레드 수와 동시 실행 수를 기록한다")
    void 위치_이벤트_처리_스레드_수를_측정한다() throws Exception {
        // given
        Probe probe = new Probe();
        willAnswer(invocation -> {
            probe.hold(LOCATION_HOLD_MS);
            return null;
        }).given(healthScheduleProgressService).completeArrivedSchedules(anyLong(), anyDouble(), anyDouble());
        double rejectedBefore = rejectedCount();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        int liveBefore = threadBean.getThreadCount();
        threadBean.resetPeakThreadCount();
        ConnectionSampler connections = ConnectionSampler.start(dataSource);
        long started = System.nanoTime();

        // when
        ExecutorService caller = Executors.newSingleThreadExecutor(named(CALLER_PREFIX));
        caller.submit(() -> {
            for (int i = 0; i < LOCATION_EVENTS; i++) {
                eventPublisher.publishEvent(new SeniorLocationUpdatedEvent((long) (i % 50) + 1, 37.5, 127.0));
            }
        }).get(60, TimeUnit.SECONDS);
        long publishMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        caller.shutdown();
        boolean drained = waitUntil(
                () -> probe.completed.get() + (int) (rejectedCount() - rejectedBefore) == LOCATION_EVENTS, 120_000);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        int peakConnections = connections.stop();

        // then
        System.out.printf("MEASUREMENT location events=%d holdMs=%d completed=%d rejected=%d drained=%s "
                        + "maxConcurrent=%d distinctThreads=%d callerThreadRuns=%d liveThreadsBefore=%d "
                        + "peakJvmThreads=%d peakActiveDbConnections=%d publishMs=%d elapsedMs=%d threadPrefixes=%s%n",
                LOCATION_EVENTS, LOCATION_HOLD_MS, probe.completed.get(), (int) (rejectedCount() - rejectedBefore),
                drained, probe.maxConcurrent.get(), probe.threads.size(), probe.callerRuns.get(), liveBefore,
                threadBean.getPeakThreadCount(), peakConnections, publishMs, elapsedMs, probe.prefixes());
        assertThat(drained).isTrue();
    }

    private double rejectedCount() {
        return meterRegistry.find("async.executor.rejected").counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    private static java.util.concurrent.ThreadFactory named(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> new Thread(runnable, prefix + seq.incrementAndGet());
    }

    private static boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    /** 대역 안에서 동시 실행 수·실행 스레드를 모은다. */
    private static final class Probe {
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();
        private final AtomicInteger completed = new AtomicInteger();
        private final AtomicInteger callerRuns = new AtomicInteger();
        private final Set<String> threads = ConcurrentHashMap.newKeySet();

        void hold(long millis) throws InterruptedException {
            String name = Thread.currentThread().getName();
            threads.add(name);
            if (name.startsWith(CALLER_PREFIX)) {
                callerRuns.incrementAndGet();
            }
            maxConcurrent.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(millis);
            } finally {
                active.decrementAndGet();
                completed.incrementAndGet();
            }
        }

        Map<String, Integer> prefixes() {
            Map<String, Integer> counts = new TreeMap<>();
            for (String thread : threads) {
                counts.merge(thread.replaceAll("\\d+$", ""), 1, Integer::sum);
            }
            return counts;
        }
    }

    /** Hikari 활성 커넥션 수의 최댓값을 5ms 간격으로 표본 추출한다. */
    private static final class ConnectionSampler {
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicInteger peak = new AtomicInteger();
        private Thread thread;

        static ConnectionSampler start(DataSource dataSource) throws java.sql.SQLException {
            ConnectionSampler sampler = new ConnectionSampler();
            HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
            sampler.thread = new Thread(() -> {
                while (sampler.running.get()) {
                    sampler.peak.accumulateAndGet(hikari.getHikariPoolMXBean().getActiveConnections(), Math::max);
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "measure-sampler");
            sampler.thread.start();
            return sampler;
        }

        int stop() throws InterruptedException {
            running.set(false);
            thread.join();
            return peak.get();
        }
    }
}
