package com.widyu.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.auth.application.SmsService;
import com.widyu.fcm.application.FcmTransport;
import com.widyu.global.security.ActiveMemberValidator;
import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.application.HeartRateService;
import com.widyu.heart.dto.request.HeartRateSingleRequest;
import com.widyu.heart.dto.response.HeartRateStatusResponse;
import com.widyu.location.realtime.application.RealtimeLocationService;
import com.widyu.location.realtime.dto.LocationUpdateResponse;
import com.widyu.sensor.application.SensorBatchService;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * #782 측정(1단계 변경 전, 2단계 변경 후 LLD-0091). 실제 websocketInboundExecutor·WebSocketConfig 위에서 느린 심박 처리(AI 지연 D 대역)가
 * 다른 세션의 위치 ACK 지연을 얼마나 늘리는지 잰다. 심박 서비스는 D만큼 막히는 대역, 위치 서비스는 즉시 응답 대역이다.
 * 로컬 루프백·대역 측정이며 운영 수치가 아니다. WS_POOL_MEASURE=1 일 때만 실행한다.
 *
 * <p>환경변수: WS_POOL_CELLS="D:H,D:H,..."(기본 0:4,2000:4,10000:4), WS_POOL_L(기본 20), WS_POOL_WARMUP_S(5),
 * WS_POOL_WINDOW_S(30), WS_POOL_DRAIN_S(15), WS_POOL_EXPECTED_CPU(있으면 availableProcessors와 같아야 함),
 * WS_POOL_OUT(결과를 덧붙일 파일).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
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
@EnabledIfEnvironmentVariable(named = "WS_POOL_MEASURE", matches = "1")
@DisplayName("WebSocket 수신 스레드풀 머리막힘 측정 (#782, 게이트)")
class WebSocketInboundPoolMeasurementTest {

    private static final String HEART_ACK = "/user/queue/heart-rate/result";
    private static final String LOCATION_ACK = "/user/queue/location/ack";
    private static final String ERRORS = "/user/queue/errors";
    private static final String INBOUND_PREFIX = "clientInboundChannel-";
    private static final String HEART_PREFIX = "websocketHeart-";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @MockBean private FcmTransport fcmTransport;
    @MockBean private com.widyu.auth.repository.TemporaryMemberRepository temporaryMemberRepository;
    @MockBean private com.widyu.auth.repository.RefreshTokenRepository refreshTokenRepository;
    @MockBean private com.widyu.auth.infrastructure.AuthLimitStore authLimitStore;
    @MockBean private com.widyu.auth.infrastructure.ClientIpResolver clientIpResolver;
    @MockBean private S3Client s3Client;
    @MockBean private SmsService smsService;
    @MockBean private net.bramp.ffmpeg.FFmpeg ffmpeg;
    @MockBean private net.bramp.ffmpeg.FFprobe ffprobe;

    @MockBean private WsTokenService wsTokenService;
    @MockBean private ActiveMemberValidator activeMemberValidator;
    @MockBean private HeartRateService heartRateService;
    @MockBean private RealtimeLocationService realtimeLocationService;
    @MockBean private SensorBatchService sensorBatchService;

    @Autowired @Qualifier("websocketInboundExecutor") private ThreadPoolTaskExecutor websocketInboundExecutor;
    @Autowired @Qualifier("clientOutboundChannel") private ExecutorSubscribableChannel clientOutboundChannel;
    @Autowired @Qualifier("websocketHeartExecutor") private ThreadPoolTaskExecutor websocketHeartExecutor;
    @Autowired @Qualifier("websocketSensorExecutor") private ThreadPoolTaskExecutor websocketSensorExecutor;

    @Autowired private org.springframework.context.ApplicationContext applicationContext;
    @Autowired private AsyncProbe asyncProbe;

    @LocalServerPort private int port;

    /** @Async 기본 실행기가 어느 스레드풀인지 확인하는 측정용 빈. */
    @org.springframework.boot.test.context.TestConfiguration
    static class AsyncProbeConfig {
        @org.springframework.context.annotation.Bean
        AsyncProbe asyncProbe() {
            return new AsyncProbe();
        }
    }

    static class AsyncProbe {
        @org.springframework.scheduling.annotation.Async
        public java.util.concurrent.CompletableFuture<String> threadName() {
            return java.util.concurrent.CompletableFuture.completedFuture(Thread.currentThread().getName());
        }
    }

    /** 셀이 끝나면 내려 대기 중인 심박 대역을 즉시 풀어 다음 셀에 밀린 작업이 남지 않게 한다. */
    private volatile CountDownLatch cellEnd = new CountDownLatch(1);
    private volatile long heartDelayMs;
    /** 심박 대역이 심박 실행기 밖에서 돈 횟수와 스레드(변경 전에는 수신 풀 밖 = CallerRuns). */
    private final AtomicInteger heartOffPool = new AtomicInteger();
    private final Set<String> heartThreads = ConcurrentHashMap.newKeySet();
    /** 위치 대역이 수신 풀 밖에서 돈 횟수와 스레드. */
    private final AtomicInteger locationOffPool = new AtomicInteger();
    private final Set<String> locationThreads = ConcurrentHashMap.newKeySet();

    @Test
    @DisplayName("느린 심박 처리 중 위치 ACK 지연과 수신 스레드풀 상태를 셀별로 기록한다")
    void 느린_심박_처리_중_위치_ACK_지연과_수신_풀_상태를_기록한다() throws Exception {
        // given
        stubCollaborators();
        int cpu = Runtime.getRuntime().availableProcessors();
        String expectedCpu = System.getenv("WS_POOL_EXPECTED_CPU");
        if (expectedCpu != null) {
            assertThat(cpu).isEqualTo(Integer.parseInt(expectedCpu));
        }
        ThreadPoolExecutor inbound = websocketInboundExecutor.getThreadPoolExecutor();
        ThreadPoolTaskExecutor clientOutboundExecutor = (ThreadPoolTaskExecutor) clientOutboundChannel.getExecutor();
        ThreadPoolExecutor outbound = clientOutboundExecutor.getThreadPoolExecutor();
        int locationSessions = intEnv("WS_POOL_L", 20);
        int warmupS = intEnv("WS_POOL_WARMUP_S", 5);
        int windowS = intEnv("WS_POOL_WINDOW_S", 30);
        int drainS = intEnv("WS_POOL_DRAIN_S", 15);
        report(String.format("[CONFIG] availableProcessors=%d inbound core=%d max=%d queueCapacity=%d keepAlive=%ds "
                        + "allowCoreTimeout=%s rejected=%s | outboundSameAsInbound=%s prefix=%s outbound core=%d max=%d queueCapacity=%d | L=%d warmup=%ds window=%ds drain=%ds",
                cpu, inbound.getCorePoolSize(), inbound.getMaximumPoolSize(), websocketInboundExecutor.getQueueCapacity(),
                inbound.getKeepAliveTime(TimeUnit.SECONDS), inbound.allowsCoreThreadTimeOut(),
                inbound.getRejectedExecutionHandler().getClass().getSimpleName(),
                clientOutboundExecutor == websocketInboundExecutor, clientOutboundExecutor.getThreadNamePrefix(),
                outbound.getCorePoolSize(), outbound.getMaximumPoolSize(), clientOutboundExecutor.getQueueCapacity(),
                locationSessions, warmupS, windowS, drainS));

        report(String.format("[CONFIG] heart core=%d max=%d queueCapacity=%d rejected=%s | sensor core=%d max=%d queueCapacity=%d rejected=%s",
                websocketHeartExecutor.getCorePoolSize(), websocketHeartExecutor.getMaxPoolSize(),
                websocketHeartExecutor.getQueueCapacity(),
                websocketHeartExecutor.getThreadPoolExecutor().getRejectedExecutionHandler().getClass().getSimpleName(),
                websocketSensorExecutor.getCorePoolSize(), websocketSensorExecutor.getMaxPoolSize(),
                websocketSensorExecutor.getQueueCapacity(),
                websocketSensorExecutor.getThreadPoolExecutor().getRejectedExecutionHandler().getClass().getSimpleName()));
        report("[CONFIG] Executor beans=" + Arrays.toString(applicationContext.getBeanNamesForType(java.util.concurrent.Executor.class))
                + " @Async default thread=" + asyncProbe.threadName().get(5, TimeUnit.SECONDS));

        // when / then
        for (String cell : System.getenv().getOrDefault("WS_POOL_CELLS", "0:4,2000:4,10000:4").split(",")) {
            String[] parts = cell.trim().split(":");
            runCell(cpu, Long.parseLong(parts[0]), Integer.parseInt(parts[1]), locationSessions, warmupS, windowS, drainS,
                    inbound, outbound);
        }
    }

    private void runCell(int cpu, long delayMs, int heartSessions, int locationSessions, int warmupS, int windowS,
                         int drainS, ThreadPoolExecutor inbound, ThreadPoolExecutor outbound) throws Exception {
        ThreadPoolExecutor heart = websocketHeartExecutor.getThreadPoolExecutor();
        ThreadPoolExecutor sensor = websocketSensorExecutor.getThreadPoolExecutor();
        awaitCoreSize(inbound);
        heartDelayMs = delayMs;
        cellEnd = new CountDownLatch(1);
        heartOffPool.set(0);
        heartThreads.clear();
        locationOffPool.set(0);
        locationThreads.clear();
        // 셀 전체(워밍업 포함) 심박 송신·ACK·과부하 오류. 셀이 끝나고 다 비운 뒤 송신 = ACK + 과부하 오류인지 본다(AC4).
        AtomicInteger heartSentTotal = new AtomicInteger();
        AtomicInteger heartAckTotal = new AtomicInteger();
        AtomicInteger overloadTotal = new AtomicInteger();
        AtomicInteger otherErrorTotal = new AtomicInteger();
        AtomicInteger overloadInWindow = new AtomicInteger();
        Map<String, Long> locationSentAt = new ConcurrentHashMap<>();
        Map<String, Long> locationLatency = new ConcurrentHashMap<>();
        Set<String> heartSent = ConcurrentHashMap.newKeySet();
        Set<String> heartAcked = ConcurrentHashMap.newKeySet();
        AtomicLong windowStart = new AtomicLong(Long.MAX_VALUE);
        AtomicLong windowEnd = new AtomicLong(Long.MAX_VALUE);
        // 드레인 마감 시점의 수신 스냅숏. 마감 뒤 지연 대역을 풀며 도착하는 ACK는 집계하지 않는다.
        Map<String, Long> latencyAtDeadline = new ConcurrentHashMap<>();
        Set<String> heartAckedAtDeadline = ConcurrentHashMap.newKeySet();

        WebSocketStompClient stompClient =
                new WebSocketStompClient(new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        List<StompSession> sessions = new ArrayList<>();
        ScheduledExecutorService senders = Executors.newScheduledThreadPool(heartSessions + locationSessions);
        Stats stats = new Stats();
        Stats heartStats = new Stats();
        Stats sensorStats = new Stats();
        Stats outboundStats = new Stats();
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(() -> {
            stats.sample(inbound);
            heartStats.sample(heart);
            sensorStats.sample(sensor);
            outboundStats.sample(outbound);
        }, 0, 50, TimeUnit.MILLISECONDS);
        long completedBefore = inbound.getCompletedTaskCount();
        int[] totals = new int[4];
        try {
            // 모든 세션의 구독이 브로커에 등록된 뒤(풀이 빈 뒤) 송신을 시작한다. 구독도 같은 수신 큐를 거친다.
            List<StompSession> heartClients = new ArrayList<>();
            for (int i = 0; i < heartSessions; i++) {
                StompSession session = connect(stompClient, 7000L + i, sessions);
                session.subscribe(HEART_ACK, handler(ack -> {
                    heartAckTotal.incrementAndGet();
                    heartAcked.add(ack.get("memberId").asText() + "|" + LocalDateTime.parse(ack.get("measuredAt").asText()));
                }));
                session.subscribe(ERRORS, handler(error -> {
                    if (!"TEMPORARILY_BUSY".equals(error.path("error").asText())) {
                        otherErrorTotal.incrementAndGet();
                        return;
                    }
                    overloadTotal.incrementAndGet();
                    // 과부하 오류는 거절 즉시(수신 시점) 오므로 도착 시각으로 측정 창 소속을 가른다.
                    if (inWindow(windowStart, windowEnd)) {
                        overloadInWindow.incrementAndGet();
                    }
                }));
                heartClients.add(session);
            }
            List<StompSession> locationClients = new ArrayList<>();
            for (int i = 0; i < locationSessions; i++) {
                StompSession session = connect(stompClient, 8000L + i, sessions);
                session.subscribe(LOCATION_ACK, handler(ack -> {
                    Long sentAt = locationSentAt.get(ack.get("name").asText());
                    if (sentAt != null) {
                        locationLatency.putIfAbsent(ack.get("name").asText(), System.nanoTime() - sentAt);
                    }
                }));
                locationClients.add(session);
            }
            TimeUnit.MILLISECONDS.sleep(500);
            awaitIdle(inbound);

            for (int i = 0; i < heartSessions; i++) {
                StompSession session = heartClients.get(i);
                long memberId = 7000L + i;
                AtomicInteger seq = new AtomicInteger();
                senders.scheduleAtFixedRate(() -> {
                    LocalDateTime measuredAt = BASE_TIME.plusSeconds(seq.incrementAndGet());
                    boolean inWindow = inWindow(windowStart, windowEnd);
                    boolean sent = send(session, "/app/heart-rate/send-single",
                            "{\"heartRate\":72,\"measuredAt\":\"" + measuredAt + "\",\"context\":\"REST\"}");
                    if (sent) {
                        heartSentTotal.incrementAndGet();
                    }
                    if (sent && inWindow) {
                        heartSent.add(memberId + "|" + measuredAt);
                    }
                }, ThreadLocalRandom.current().nextLong(1000), 1000, TimeUnit.MILLISECONDS);
            }
            for (int i = 0; i < locationSessions; i++) {
                StompSession session = locationClients.get(i);
                String label = "L" + i;
                AtomicInteger seq = new AtomicInteger();
                senders.scheduleAtFixedRate(() -> {
                    String tag = label + "-" + seq.incrementAndGet();
                    if (inWindow(windowStart, windowEnd)) {
                        locationSentAt.put(tag, System.nanoTime());
                    }
                    send(session, "/app/location/update", "{\"tag\":\"" + tag + "\"}");
                }, ThreadLocalRandom.current().nextLong(1000), 1000, TimeUnit.MILLISECONDS);
            }
            TimeUnit.SECONDS.sleep(warmupS);
            stats.reset();
            windowStart.set(System.nanoTime());
            TimeUnit.SECONDS.sleep(windowS);
            windowEnd.set(System.nanoTime());
            senders.shutdownNow();
            long drainDeadline = System.currentTimeMillis() + drainS * 1000L;
            while (locationLatency.size() < locationSentAt.size() && System.currentTimeMillis() < drainDeadline) {
                TimeUnit.MILLISECONDS.sleep(50);
            }
            latencyAtDeadline.putAll(locationLatency);
            heartAckedAtDeadline.addAll(heartAcked);
        } finally {
            senders.shutdownNow();
            sampler.shutdownNow();
            cellEnd.countDown();
            awaitIdle(inbound);
            awaitIdle(heart);
            awaitIdle(outbound);
            awaitQuiet(() -> heartAckTotal.get() + overloadTotal.get() + otherErrorTotal.get());
            totals[0] = heartSentTotal.get();
            totals[1] = heartAckTotal.get();
            totals[2] = overloadTotal.get();
            totals[3] = otherErrorTotal.get();
            sessions.forEach(this::disconnectQuietly);
            stompClient.stop();
        }

        long[] latencies = new long[locationSentAt.size()];
        int idx = 0;
        for (String tag : locationSentAt.keySet()) {
            Long nanos = latencyAtDeadline.get(tag);
            if (nanos == null) {
                latencies[idx++] = Long.MAX_VALUE;
            } else {
                latencies[idx++] = nanos;
            }
        }
        Arrays.sort(latencies);
        int heartAckInWindow = 0;
        for (String key : heartSent) {
            if (heartAckedAtDeadline.contains(key)) {
                heartAckInWindow++;
            }
        }
        report(String.format("[MEASURE] C=%d D=%dms H=%d L=%d | location ack recv/sent=%d/%d p50=%s p95=%s p99=%s max=%s "
                        + "| heart ack recv/sent=%d/%d overloadInWindow=%d | heart total sent=%d ack=%d overload=%d otherErrors=%d unaccounted=%d "
                        + "| inbound poolMax=%d activeMax=%d queueMax=%d largestPool=%d completedDuringCell=%d "
                        + "| heart poolMax=%d activeMax=%d queueMax=%d | sensor poolMax=%d queueMax=%d | outbound poolMax=%d activeMax=%d queueMax=%d "
                        + "| heartOffPool=%d heartThreads=%s locationOffPool=%d locationThreads=%s",
                cpu, delayMs, heartSessions, locationSessions, latencyAtDeadline.size(), locationSentAt.size(),
                pct(latencies, 0.50), pct(latencies, 0.95), pct(latencies, 0.99), pct(latencies, 1.0),
                heartAckInWindow, heartSent.size(), overloadInWindow.get(),
                totals[0], totals[1], totals[2], totals[3], totals[0] - totals[1] - totals[2] - totals[3],
                stats.poolMax, stats.activeMax, stats.queueMax,
                inbound.getLargestPoolSize(), inbound.getCompletedTaskCount() - completedBefore,
                heartStats.poolMax, heartStats.activeMax, heartStats.queueMax, sensorStats.poolMax, sensorStats.queueMax,
                outboundStats.poolMax, outboundStats.activeMax, outboundStats.queueMax,
                heartOffPool.get(), heartThreads, locationOffPool.get(), locationThreads));
    }

    private static boolean inWindow(AtomicLong start, AtomicLong end) {
        long now = System.nanoTime();
        return now >= start.get() && now < end.get();
    }

    /** 누락분은 무한대로 보고 전체 송신 수 기준으로 분위수를 잡는다. 받은 것만으로 계산하면 지연이 작게 보인다. */
    private static String pct(long[] sorted, double q) {
        if (sorted.length == 0) {
            return "n/a";
        }
        int i = (int) Math.ceil(q * sorted.length) - 1;
        long value = sorted[Math.max(i, 0)];
        if (value == Long.MAX_VALUE) {
            return "not-received";
        }
        return TimeUnit.NANOSECONDS.toMillis(value) + "ms";
    }

    /** 수신 수가 1초 동안 늘지 않을 때까지 기다린다(최대 30초). */
    private static void awaitQuiet(java.util.function.IntSupplier total) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000L;
        int last;
        do {
            last = total.getAsInt();
            TimeUnit.MILLISECONDS.sleep(1000);
        } while (total.getAsInt() != last && System.currentTimeMillis() < deadline);
    }

    private void awaitIdle(ThreadPoolExecutor inbound) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000L;
        while ((inbound.getQueue().size() > 0 || inbound.getActiveCount() > 0) && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
    }

    /** 앞 셀에서 core 너머로 늘어난 스레드가 keepAlive로 줄어들 때까지 기다려 다음 셀 출발 조건을 맞춘다. */
    private void awaitCoreSize(ThreadPoolExecutor inbound) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 90_000L;
        while (inbound.getPoolSize() > inbound.getCorePoolSize() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(500);
        }
    }

    private StompSession connect(WebSocketStompClient stompClient, long memberId, List<StompSession> sessions) throws Exception {
        StompSession session = stompClient
                .connectAsync("http://localhost:" + port + "/ws/location?token=member-" + memberId + "-A",
                        new WebSocketHttpHeaders(), new StompHeaders(), new StompSessionHandlerAdapter() {
                        })
                .get(10, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    private void disconnectQuietly(StompSession session) {
        try {
            session.disconnect();
        } catch (RuntimeException ignored) {
            // 측정 종료 정리. 이미 끊긴 세션은 무시한다.
        }
    }

    private static StompFrameHandler handler(java.util.function.Consumer<JsonNode> onAck) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                try {
                    onAck.accept(JSON.readTree((byte[]) payload));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private static boolean send(StompSession session, String destination, String json) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination(destination);
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        try {
            session.send(headers, json.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (RuntimeException ignored) {
            // 셀 종료 직후 끊긴 세션에 보내는 경우. 측정에서 송신 수로 세지 않는다.
            return false;
        }
    }

    private void stubCollaborators() {
        given(wsTokenService.validateAndConsume(anyString()))
                .willAnswer(invocation -> Long.parseLong(invocation.getArgument(0, String.class).split("-")[1]));
        given(activeMemberValidator.isActive(anyLong())).willReturn(true);
        given(heartRateService.processHeartRate(anyLong(), any(HeartRateSingleRequest.class)))
                .willAnswer(invocation -> {
                    noteCaller(HEART_PREFIX, heartOffPool, heartThreads);
                    // 동기 AI 호출(HeartRateAnomalyDetector) 대역: 처리 스레드(변경 전 수신, 변경 후 심박 실행기)를 D만큼 점유한다.
                    cellEnd.await(heartDelayMs, TimeUnit.MILLISECONDS);
                    HeartRateSingleRequest request = invocation.getArgument(1);
                    return HeartRateStatusResponse.of(invocation.getArgument(0), HeartRateStatus.NORMAL,
                            request.heartRate(), request.measuredAt());
                });
        given(realtimeLocationService.updateAndBroadcast(any(byte[].class), anyLong()))
                .willAnswer(invocation -> {
                    noteCaller(INBOUND_PREFIX, locationOffPool, locationThreads);
                    String tag = JSON.readTree(invocation.getArgument(0, byte[].class)).get("tag").asText();
                    return LocationUpdateResponse.of(invocation.getArgument(1), tag, null,
                            37.5, 127.0, LocalDateTime.now(), "OTHER", null);
                });
    }

    /** 기대한 실행기 밖(예: CallerRuns로 Tomcat 수신 스레드)에서 실행됐는지 센다. 실행한 스레드 이름(번호 제외)도 모은다. */
    private static void noteCaller(String expectedPrefix, AtomicInteger offPool, Set<String> threads) {
        String name = Thread.currentThread().getName();
        threads.add(name.replaceAll("\\d+$", "N"));
        if (!name.startsWith(expectedPrefix)) {
            offPool.incrementAndGet();
        }
    }

    private static int intEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }

    private static void report(String line) throws IOException {
        System.out.println(line);
        String out = System.getenv("WS_POOL_OUT");
        if (out != null) {
            Files.writeString(Path.of(out), line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private static final class Stats {
        private volatile int poolMax;
        private volatile int activeMax;
        private volatile int queueMax;

        private void sample(ThreadPoolExecutor executor) {
            poolMax = Math.max(poolMax, executor.getPoolSize());
            activeMax = Math.max(activeMax, executor.getActiveCount());
            queueMax = Math.max(queueMax, executor.getQueue().size());
        }

        private void reset() {
            poolMax = 0;
            activeMax = 0;
            queueMax = 0;
        }
    }
}
