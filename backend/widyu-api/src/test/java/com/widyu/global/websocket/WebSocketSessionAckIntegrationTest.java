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
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.application.HeartRateService;
import com.widyu.heart.dto.request.HeartRateSingleRequest;
import com.widyu.heart.dto.response.HeartRateStatusResponse;
import com.widyu.location.realtime.application.RealtimeLocationService;
import com.widyu.location.realtime.dto.LocationUpdateResponse;
import com.widyu.member.MemberRole;
import com.widyu.sensor.application.SensorBatchService;
import com.widyu.sensor.dto.response.SensorBatchResult;
import com.widyu.sensor.dto.response.SensorBatchResultResponse;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.UserDestinationMessageHandler;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
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
 * 실제 STOMP 클라이언트(SockJS)로 요청 응답이 어느 세션에 도착하는지 센다(LLD-0088 AC4·AC5·AC7, LLD-0091 AC2·AC3).
 * 인터셉터·브로커·컨트롤러는 실제 빈이고, 토큰 저장소·회원 활성 확인·업무 서비스만 대역이다.
 * 각 시나리오는 `[MEASURE]` 한 줄로 수신 수를 남긴 뒤 단언한다.
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
        "coolsms.message-template=인증번호: {code}",
        // 라우팅을 보는 테스트라 연속 전송 N건이 모두 대기열에 들어가게 한다. 과부하 거절은 WebSocketWorkDispatcherTest가 본다.
        "websocket.executor.heart.queue-capacity=1000",
        "websocket.executor.sensor.queue-capacity=1000"
})
@DisplayName("WebSocket 요청 응답 세션 라우팅 통합 테스트")
class WebSocketSessionAckIntegrationTest {

    private static final int N = 100;
    private static final long ARRIVAL_TIMEOUT_MS = 5_000L;
    private static final long QUIET_MS = 500L;
    private static final String HEART_ACK = "/user/queue/heart-rate/result";
    private static final String LOCATION_ACK = "/user/queue/location/ack";
    private static final String SENSOR_ACK = "/user/queue/sensor/result";
    private static final String ERRORS = "/user/queue/errors";
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

    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired @Qualifier("clientInboundChannel") private ExecutorSubscribableChannel clientInboundChannel;
    @Autowired @Qualifier("clientOutboundChannel") private ExecutorSubscribableChannel clientOutboundChannel;
    @Autowired @Qualifier("websocketInboundExecutor") private ThreadPoolTaskExecutor websocketInboundExecutor;
    @Autowired @Qualifier("websocketOutboundExecutor") private ThreadPoolTaskExecutor websocketOutboundExecutor;

    /** 업무 서비스 대역이 실행된 스레드 이름(번호 제외). 느린 처리가 전용 실행기에서 도는지 본다(LLD-0091 AC2). */
    private final Set<String> heartThreads = ConcurrentHashMap.newKeySet();
    private final Set<String> sensorThreads = ConcurrentHashMap.newKeySet();

    @LocalServerPort private int port;

    @Test
    @DisplayName("일회용 토큰으로만 연결한 세션이 심박·위치·센서를 보내면 각 ACK를 요청 수만큼 받는다")
    void 일회용_토큰_세션이_심박_위치_센서를_보내면_각_ACK를_요청_수만큼_받는다() throws Exception {
        // given
        stubCollaborators();
        long memberId = 9001L;
        try (Harness harness = new Harness()) {
            Client client = harness.connect("A", memberId, null);

            // when
            for (int seq = 0; seq < N; seq++) {
                client.sendHeart(seq);
                client.sendLocation(seq);
                client.sendSensor(seq);
            }
            awaitQuiet(() -> client.total(), 3 * N);

            // then
            int heart = client.received(HEART_ACK).size();
            int location = client.received(LOCATION_ACK).size();
            int sensor = client.received(SENSOR_ACK).size();
            System.out.printf("[MEASURE] S1 token-only single session N=%d heart ack=%d/%d location ack=%d/%d sensor ack=%d/%d "
                            + "heart threads=%s sensor threads=%s%n",
                    N, heart, N, location, N, sensor, N, heartThreads, sensorThreads);
            assertThat(heart).isEqualTo(N);
            assertThat(location).isEqualTo(N);
            assertThat(sensor).isEqualTo(N);
            assertThat(heartThreads).containsOnly("websocketHeart-");
            assertThat(sensorThreads).containsOnly("websocketSensor-");
        }
    }

    @Test
    @DisplayName("같은 회원이 일회용 토큰 두 개로 연결해 요청하면 각 세션은 자기 ACK만 받는다")
    void 같은_회원의_두_세션이_요청하면_각_세션은_자기_ACK만_받는다() throws Exception {
        // given
        stubCollaborators();
        long memberId = 9002L;
        try (Harness harness = new Harness()) {
            Client a = harness.connect("A", memberId, null);
            Client b = harness.connect("B", memberId, null);

            // when
            for (int seq = 0; seq < N; seq++) {
                a.sendHeart(seq);
                a.sendLocation(seq);
                b.sendHeart(seq);
                b.sendLocation(seq);
            }
            awaitQuiet(() -> a.total() + b.total(), 4 * N);

            // then
            Counts aHeart = a.heartCounts(b);
            Counts bHeart = b.heartCounts(a);
            Counts aLocation = a.locationCounts(b);
            Counts bLocation = b.locationCounts(a);
            System.out.printf("[MEASURE] S2 two token-only sessions same member N=%d "
                            + "heart A own=%d cross=%d B own=%d cross=%d | location A own=%d cross=%d B own=%d cross=%d%n",
                    N, aHeart.own, aHeart.cross, bHeart.own, bHeart.cross,
                    aLocation.own, aLocation.cross, bLocation.own, bLocation.cross);
            assertThat(List.of(aHeart.own, bHeart.own, aLocation.own, bLocation.own)).containsOnly(N);
            assertThat(List.of(aHeart.cross, bHeart.cross, aLocation.cross, bLocation.cross)).containsOnly(0);
        }
    }

    @Test
    @DisplayName("CONNECT에 JWT를 실은 같은 회원 세션이 있어도 토큰 세션의 심박 ACK는 토큰 세션에만 간다")
    void CONNECT_JWT_세션이_있어도_토큰_세션의_심박_ACK는_토큰_세션에만_간다() throws Exception {
        // given
        stubCollaborators();
        long memberId = 9003L;
        String jwt = jwtTokenProvider.generateAccessToken(memberId, MemberRole.USER, "LOCAL");
        try (Harness harness = new Harness()) {
            Client a = harness.connect("A", memberId, null);
            Client b = harness.connect("B", memberId, jwt);

            // when
            for (int seq = 0; seq < N; seq++) {
                a.sendHeart(seq);
            }
            awaitQuiet(() -> a.total() + b.total(), N);

            // then
            Counts aHeart = a.heartCounts(b);
            Counts bHeart = b.heartCounts(a);
            System.out.printf("[MEASURE] S3 mixed (A token-only, B token+CONNECT JWT) A sends N=%d heart "
                            + "A received=%d B received(A's)=%d%n",
                    N, aHeart.own, bHeart.cross);
            assertThat(aHeart.own).isEqualTo(N);
            assertThat(bHeart.cross).isZero();
        }
    }

    @Test
    @DisplayName("한 세션의 요청이 실패하면 오류 응답은 그 세션에만 간다")
    void 한_세션의_요청이_실패하면_오류_응답은_그_세션에만_간다() throws Exception {
        // given
        stubCollaborators();
        long tokenOnlyMember = 9004L;
        long jwtMember = 9005L;
        String jwt = jwtTokenProvider.generateAccessToken(jwtMember, MemberRole.USER, "LOCAL");
        try (Harness harness = new Harness()) {
            Client tokenA = harness.connect("A", tokenOnlyMember, null);
            Client tokenB = harness.connect("B", tokenOnlyMember, null);
            Client jwtA = harness.connect("A", jwtMember, jwt);
            Client jwtB = harness.connect("B", jwtMember, jwt);

            // when
            for (int seq = 0; seq < N; seq++) {
                tokenA.sendInvalidHeart();
                jwtA.sendInvalidHeart();
                jwtA.sendLocation(seq);
            }
            awaitQuiet(() -> tokenA.total() + tokenB.total() + jwtA.total() + jwtB.total(), 3 * N);

            // then
            int tokenAErrors = tokenA.received(ERRORS).size();
            int tokenBErrors = tokenB.received(ERRORS).size();
            int jwtAErrors = jwtA.received(ERRORS).size();
            int jwtBErrors = jwtB.received(ERRORS).size();
            Counts jwtALocation = jwtA.locationCounts(jwtB);
            Counts jwtBLocation = jwtB.locationCounts(jwtA);
            System.out.printf("[MEASURE] S4 errors N=%d token-only pair A=%d B=%d | CONNECT-JWT pair A=%d B=%d "
                            + "| CONNECT-JWT pair location A own=%d B received(A's)=%d%n",
                    N, tokenAErrors, tokenBErrors, jwtAErrors, jwtBErrors, jwtALocation.own, jwtBLocation.cross);
            assertThat(List.of(tokenAErrors, jwtAErrors, jwtALocation.own)).containsOnly(N);
            assertThat(List.of(tokenBErrors, jwtBErrors, jwtBLocation.cross)).containsOnly(0);
        }
    }

    @Test
    @DisplayName("요청 본문 내용과 관계없이 연결 시 인증한 회원으로 처리한다")
    void 요청_본문과_관계없이_연결_시_인증한_회원으로_처리한다() throws Exception {
        // given
        stubCollaborators();
        long memberId = 9006L;
        long otherMemberId = 4242L;
        String extraFields = "\"memberId\":" + otherMemberId + ",\"role\":\"USER\"";
        try (Harness harness = new Harness()) {
            Client client = harness.connect("A", memberId, null);

            // when
            for (int seq = 0; seq < N; seq++) {
                client.send("/app/location/update", "{" + extraFields + ",\"tag\":\"extra-" + seq + "\"}");
                client.send("/app/sensor/batches/send", "{" + extraFields + ",\"batch_id\":\"extra-" + seq + "\",\"seq\":" + seq + "}");
                client.send("/app/heart-rate/send-single", "{" + extraFields + ",\"heartRate\":72,\"measuredAt\":\""
                        + BASE_TIME.plusSeconds(seq + 1L) + "\"}");
            }
            awaitQuiet(() -> processedMemberIds().size(), 3 * N);

            // then
            List<Long> processed = processedMemberIds();
            long asOther = processed.stream().filter(id -> id == otherMemberId).count();
            long asSelf = processed.stream().filter(id -> id == memberId).count();
            System.out.printf("[MEASURE] S5 payload memberId field N=%d x3 types processed=%d as payload member=%d as connected member=%d%n",
                    N, processed.size(), asOther, asSelf);
            assertThat(asOther).isZero();
            assertThat(asSelf).isEqualTo(3L * N);
        }
    }

    @Test
    @DisplayName("응답 채널은 수신 실행기가 아닌 응답 전용 실행기를 쓴다")
    void 응답_채널은_응답_전용_실행기를_쓴다() {
        // when / then
        assertThat(clientInboundChannel.getExecutor()).isSameAs(websocketInboundExecutor);
        assertThat(clientOutboundChannel.getExecutor()).isSameAs(websocketOutboundExecutor);
    }

    /** 업무 서비스 대역이 받은 회원 ID. 컨트롤러가 어느 회원으로 요청을 처리했는지 보여준다. */
    private List<Long> processedMemberIds() {
        List<Long> memberIds = new ArrayList<>();
        for (Object service : List.of(heartRateService, realtimeLocationService, sensorBatchService)) {
            Mockito.mockingDetails(service).getInvocations().forEach(invocation -> {
                for (Object argument : invocation.getArguments()) {
                    if (argument instanceof Long id) {
                        memberIds.add(id);
                    }
                }
            });
        }
        return memberIds;
    }

    private void stubCollaborators() {
        // 토큰 문자열 "member-<회원 ID>-<라벨>"을 그 회원으로 소비한다.
        given(wsTokenService.validateAndConsume(anyString()))
                .willAnswer(invocation -> Long.parseLong(invocation.getArgument(0, String.class).split("-")[1]));
        given(activeMemberValidator.isActive(anyLong())).willReturn(true);
        given(heartRateService.processHeartRate(anyLong(), any(HeartRateSingleRequest.class)))
                .willAnswer(invocation -> {
                    heartThreads.add(threadPrefix());
                    HeartRateSingleRequest request = invocation.getArgument(1);
                    return HeartRateStatusResponse.of(invocation.getArgument(0), HeartRateStatus.NORMAL,
                            request.heartRate(), request.measuredAt());
                });
        given(realtimeLocationService.updateAndBroadcast(any(byte[].class), anyLong()))
                .willAnswer(invocation -> {
                    String tag = JSON.readTree(invocation.getArgument(0, byte[].class)).get("tag").asText();
                    return LocationUpdateResponse.of(invocation.getArgument(1), tag, null,
                            37.5, 127.0, LocalDateTime.now(), "OTHER", null);
                });
        given(sensorBatchService.ingest(anyLong(), any(byte[].class)))
                .willAnswer(invocation -> {
                    sensorThreads.add(threadPrefix());
                    JsonNode root = JSON.readTree(invocation.getArgument(1, byte[].class));
                    return SensorBatchResultResponse.of(root.get("batch_id").asText(), root.get("seq").asLong(),
                            SensorBatchResult.STORED);
                });
    }

    private static String threadPrefix() {
        return Thread.currentThread().getName().replaceAll("\\d+$", "");
    }

    /** 기대 수신 수에 닿거나 시간이 지나면, 늦게 오는 교차 전달까지 세도록 수신 수가 멈출 때까지 기다린다. */
    private static void awaitQuiet(IntSupplier total, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ARRIVAL_TIMEOUT_MS;
        while (total.getAsInt() < expected && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        int last;
        do {
            last = total.getAsInt();
            TimeUnit.MILLISECONDS.sleep(QUIET_MS);
        } while (total.getAsInt() != last);
    }

    private record Counts(int own, int cross) {
    }

    /** 테스트 하나에서 연 세션과 구독 처리 확인용 인터셉터를 정리한다. */
    private final class Harness implements AutoCloseable {

        private final AtomicInteger handledUserSubscriptions = new AtomicInteger();
        private final List<StompSession> sessions = new ArrayList<>();
        private final WebSocketStompClient stompClient =
                new WebSocketStompClient(new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        private final ExecutorChannelInterceptor subscriptionProbe = new ExecutorChannelInterceptor() {
            @Override
            public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler,
                                            Exception ex) {
                if (handler instanceof UserDestinationMessageHandler
                        && SimpMessageType.SUBSCRIBE.equals(SimpMessageHeaderAccessor.getMessageType(message.getHeaders()))) {
                    handledUserSubscriptions.incrementAndGet();
                }
            }
        };

        private Harness() {
            clientInboundChannel.addInterceptor(subscriptionProbe);
        }

        /** 일회용 토큰으로 핸드셰이크하고, connectJwt가 있으면 CONNECT 헤더에 싣는다. 구독이 브로커에 등록될 때까지 기다린다. */
        private Client connect(String label, long memberId, String connectJwt) throws Exception {
            String token = "member-" + memberId + "-" + label;
            StompHeaders connectHeaders = new StompHeaders();
            if (connectJwt != null) {
                connectHeaders.add("Authorization", "Bearer " + connectJwt);
            }
            StompSession session = stompClient
                    .connectAsync("http://localhost:" + port + "/ws/location?token=" + token,
                            new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {
                            })
                    .get(5, TimeUnit.SECONDS);
            sessions.add(session);

            int expectedSubscriptions = handledUserSubscriptions.get() + 4;
            Client client = new Client(label, session);
            client.subscribeAll();
            long deadline = System.currentTimeMillis() + ARRIVAL_TIMEOUT_MS;
            while (handledUserSubscriptions.get() < expectedSubscriptions && System.currentTimeMillis() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            assertThat(handledUserSubscriptions.get()).isGreaterThanOrEqualTo(expectedSubscriptions);
            return client;
        }

        @Override
        public void close() {
            clientInboundChannel.removeInterceptor(subscriptionProbe);
            sessions.forEach(StompSession::disconnect);
            stompClient.stop();
        }
    }

    /** 한 STOMP 세션. 보낸 요청의 식별값을 남겨 수신한 ACK가 자기 것인지 다른 세션 것인지 가른다. */
    private static final class Client {

        private final String label;
        private final StompSession session;
        private final Map<String, Queue<JsonNode>> received = Map.of(
                HEART_ACK, new ConcurrentLinkedQueue<>(),
                LOCATION_ACK, new ConcurrentLinkedQueue<>(),
                SENSOR_ACK, new ConcurrentLinkedQueue<>(),
                ERRORS, new ConcurrentLinkedQueue<>());
        private final Set<LocalDateTime> sentHeart = new HashSet<>();
        private final Set<String> sentLocation = new HashSet<>();

        private Client(String label, StompSession session) {
            this.label = label;
            this.session = session;
        }

        private void subscribeAll() {
            received.forEach((destination, queue) -> session.subscribe(destination, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return byte[].class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    try {
                        queue.add(JSON.readTree((byte[]) payload));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            }));
        }

        private void sendHeart(int seq) {
            // 세션 라벨마다 다른 날짜를 써 measuredAt만으로 어느 세션 요청인지 구분한다.
            LocalDateTime measuredAt = BASE_TIME.plusDays(label.charAt(0)).plusSeconds(seq + 1L);
            sentHeart.add(measuredAt);
            send("/app/heart-rate/send-single",
                    "{\"heartRate\":72,\"measuredAt\":\"" + measuredAt + "\",\"context\":\"REST\"}");
        }

        private void sendInvalidHeart() {
            send("/app/heart-rate/send-single", "{\"heartRate\":0,\"measuredAt\":\"" + BASE_TIME + "\"}");
        }

        private void sendLocation(int seq) {
            String tag = label + "-L-" + seq;
            sentLocation.add(tag);
            send("/app/location/update", "{\"tag\":\"" + tag + "\"}");
        }

        private void sendSensor(int seq) {
            send("/app/sensor/batches/send", "{\"batch_id\":\"" + label + "-S-" + seq + "\",\"seq\":" + seq + "}");
        }

        private void send(String destination, String json) {
            StompHeaders headers = new StompHeaders();
            headers.setDestination(destination);
            headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
            session.send(headers, json.getBytes(StandardCharsets.UTF_8));
        }

        private Queue<JsonNode> received(String destination) {
            return received.get(destination);
        }

        private int total() {
            return received.values().stream().mapToInt(Queue::size).sum();
        }

        private Counts heartCounts(Client other) {
            int own = 0;
            int cross = 0;
            for (JsonNode ack : received(HEART_ACK)) {
                LocalDateTime measuredAt = LocalDateTime.parse(ack.get("measuredAt").asText());
                if (sentHeart.contains(measuredAt)) {
                    own++;
                } else if (other.sentHeart.contains(measuredAt)) {
                    cross++;
                }
            }
            return new Counts(own, cross);
        }

        private Counts locationCounts(Client other) {
            int own = 0;
            int cross = 0;
            for (JsonNode ack : received(LOCATION_ACK)) {
                String tag = ack.get("name").asText();
                if (sentLocation.contains(tag)) {
                    own++;
                } else if (other.sentLocation.contains(tag)) {
                    cross++;
                }
            }
            return new Counts(own, cross);
        }
    }
}
