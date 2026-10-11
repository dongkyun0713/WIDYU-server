package com.widyu.fcm.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.widyu.fcm.NotificationType;
import com.widyu.fcm.dto.FcmSendDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;

class FcmHttpTransportTest {
    @Test
    @DisplayName("본인확인 푸시를 전송하면 안전 채널과 시간 민감 표시를 포함한다")
    void 본인확인_푸시를_전송하면_안전_표시를_포함한다() throws Exception {
        // given
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = payloadServer(mapper, bodies);
        FcmHttpTransport transport = new FcmHttpTransport(endpoint(server), mapper,
                () -> "loopback-access-token", Duration.ofSeconds(2));
        FcmSendDto message = FcmSendDto.builder().title("안전 확인").content("괜찮으세요?")
                .notificationType(NotificationType.SAFETY_SELF_CHECK)
                .data(Map.of("eventId", "inc-20261001-1", "deepLink", "widyu://incident/inc-20261001-1"))
                .build();
        try {
            // when
            assertThat(transport.send("loopback-token", message).success()).isTrue();

            // then
            JsonNode body = bodies.getFirst();
            assertThat(body.at("/message/data/type").asText()).isEqualTo("SAFETY_SELF_CHECK");
            assertThat(body.at("/message/data/notificationType").asText()).isEqualTo("SAFETY_SELF_CHECK");
            assertThat(body.at("/message/data/priority").asText()).isEqualTo("critical");
            assertThat(body.at("/message/data/deepLink").asText())
                    .isEqualTo("widyu://incident/inc-20261001-1");
            assertThat(body.at("/message/android/priority").asText()).isEqualTo("high");
            assertThat(body.at("/message/android/notification/channel_id").asText()).isEqualTo("widyu_safety");
            assertThat(body.at("/message/apns/payload/aps/interruption-level").asText())
                    .isEqualTo("time-sensitive");
        } finally {
            transport.close();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("안전 타입을 전송하면 Android 채널과 APNs 긴급 표시를 포함한다")
    void 안전_타입을_전송하면_긴급_표시를_포함한다() throws Exception {
        // given
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = payloadServer(mapper, bodies);
        FcmHttpTransport transport = new FcmHttpTransport(endpoint(server), mapper,
                () -> "loopback-access-token", Duration.ofSeconds(2));
        FcmSendDto message = FcmSendDto.builder().title("안전 알림").content("위치를 확인해주세요.")
                .notificationType(NotificationType.HEART_RATE_EMERGENCY)
                .data(Map.of("eventId", "event-1", "deepLink", "/location?seniorId=17",
                        "seniorId", "17")).build();
        FcmDelivery delivery = new FcmDelivery(4072L, 1, "loopback-token", message,
                Instant.now().plusSeconds(300), 8111L);
        try {
            // when
            assertThat(transport.send(delivery, () -> true).success()).isTrue();

            // then
            JsonNode body = bodies.getFirst();
            assertThat(body.at("/message/data/eventId").asText()).isEqualTo("event-1");
            assertThat(body.at("/message/data/type").asText()).isEqualTo("HEART_RATE_EMERGENCY");
            assertThat(body.at("/message/data/priority").asText()).isEqualTo("critical");
            assertThat(body.at("/message/data/notificationId").asText()).isEqualTo("8111");
            assertThat(body.at("/message/data/deepLink").asText()).isEqualTo("/location?seniorId=17");
            assertThat(body.at("/message/data/foregroundPresentation").asText()).isEqualTo("MODAL");
            assertThat(body.at("/message/android/priority").asText()).isEqualTo("high");
            assertThat(body.at("/message/android/notification/channel_id").asText()).isEqualTo("widyu_safety");
            assertThat(body.at("/message/apns/headers/apns-priority").asText()).isEqualTo("10");
            assertThat(body.at("/message/apns/payload/aps/interruption-level").asText())
                    .isEqualTo("time-sensitive");
        } finally {
            transport.close();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("일반 타입을 전송하면 일반 채널과 추가 data를 포함한다")
    void 일반_타입을_전송하면_일반_채널과_추가_data를_포함한다() throws Exception {
        // given
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = payloadServer(mapper, bodies);
        FcmHttpTransport transport = new FcmHttpTransport(endpoint(server), mapper,
                () -> "loopback-access-token", Duration.ofSeconds(2));
        FcmSendDto message = FcmSendDto.builder().title("복약 일정 등록").content("내일부터 적용돼요.")
                .notificationType(NotificationType.MEDICATION_SCHEDULE_CREATED)
                .data(Map.of("eventId", "event-2", "type", "stale-type",
                        "revision", "42",
                        "effectiveFromDate", "2026-10-02", "actorDisplayName", "보호자",
                        "deepLink", "widyu://medication/schedules"))
                .build();
        FcmDelivery delivery = new FcmDelivery(4073L, 1, "loopback-token", message,
                Instant.now().plusSeconds(300));
        try {
            // when
            assertThat(transport.send(delivery, () -> true).success()).isTrue();

            // then
            JsonNode body = bodies.getFirst();
            assertThat(body.at("/message/data/type").asText()).isEqualTo("MEDICATION_SCHEDULE_CHANGED");
            assertThat(body.at("/message/data/notificationType").asText())
                    .isEqualTo("MEDICATION_SCHEDULE_CREATED");
            assertThat(body.at("/message/data/priority").asText()).isEqualTo("interaction");
            assertThat(body.at("/message/data/revision").asText()).isEqualTo("42");
            assertThat(body.at("/message/data/effectiveFromDate").asText()).isEqualTo("2026-10-02");
            assertThat(body.at("/message/data/actorDisplayName").asText()).isEqualTo("보호자");
            assertThat(body.at("/message/android/priority").asText()).isEqualTo("normal");
            assertThat(body.at("/message/android/notification/channel_id").asText()).isEqualTo("widyu_general");
            assertThat(body.at("/message/apns/payload/aps/interruption-level").asText()).isEqualTo("active");
        } finally {
            transport.close();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("동기화 타입을 전송하면 표시 알림 없이 data만 포함한다")
    void 동기화_타입을_전송하면_표시_알림_없이_data만_포함한다() throws Exception {
        // given
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = payloadServer(mapper, bodies);
        FcmHttpTransport transport = new FcmHttpTransport(endpoint(server), mapper,
                () -> "loopback-access-token", Duration.ofSeconds(2));
        FcmSendDto message = FcmSendDto.builder().notificationType(NotificationType.MEDICATION_SCHEDULE_SYNC)
                .data(Map.of("eventId", "event-3", "revision", "43")).build();
        FcmDelivery delivery = new FcmDelivery(4074L, 1, "loopback-token", message,
                Instant.now().plusSeconds(300), null);
        try {
            // when
            assertThat(transport.send(delivery, () -> true).success()).isTrue();

            // then
            JsonNode body = bodies.getFirst();
            assertThat(body.at("/message/notification").isMissingNode()).isTrue();
            assertThat(body.at("/message/data/type").asText()).isEqualTo("MEDICATION_SCHEDULE_CHANGED");
            assertThat(body.at("/message/data/notificationType").asText())
                    .isEqualTo("MEDICATION_SCHEDULE_SYNC");
            assertThat(body.at("/message/data/revision").asText()).isEqualTo("43");
            assertThat(body.at("/message/data/notificationId").isMissingNode()).isTrue();
            assertThat(body.at("/message/data/foregroundPresentation").asText()).isEqualTo("NONE");
            assertThat(body.at("/message/android/notification").isMissingNode()).isTrue();
            assertThat(body.at("/message/apns/headers/apns-priority").asText()).isEqualTo("5");
            assertThat(body.at("/message/apns/headers/apns-push-type").asText()).isEqualTo("background");
            assertThat(body.at("/message/apns/payload/aps/content-available").asInt()).isEqualTo(1);
        } finally {
            transport.close();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("복약 미인증과 후속 안내와 수동 타입을 전송하면 각 우선순위를 포함한다")
    void 복약_미인증과_후속_안내와_수동_타입을_전송하면_우선순위를_구분한다() throws Exception {
        // given
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = payloadServer(mapper, bodies);
        FcmHttpTransport transport = new FcmHttpTransport(endpoint(server), mapper,
                () -> "loopback-access-token", Duration.ofSeconds(2));
        FcmSendDto missing = FcmSendDto.builder().title("복약 미인증").content("확인해주세요.")
                .notificationType(NotificationType.MEDICATION_PROOF_MISSING).build();
        FcmSendDto reminder = FcmSendDto.builder().title("복약 확인").content("인증해주세요.")
                .notificationType(NotificationType.MEDICATION_REMINDER_10).build();
        FcmSendDto walk = FcmSendDto.builder().title("걷기 목표").content("확인해주세요.")
                .notificationType(NotificationType.WALK_GOAL_UNMET).build();
        try {
            // when
            assertThat(transport.send("loopback-token", missing).success()).isTrue();
            assertThat(transport.send("loopback-token", reminder).success()).isTrue();
            assertThat(transport.send("loopback-token", walk).success()).isTrue();

            // then
            assertThat(bodies.get(0).at("/message/android/priority").asText()).isEqualTo("high");
            assertThat(bodies.get(0).at("/message/apns/payload/aps/interruption-level").asText())
                    .isEqualTo("time-sensitive");
            assertThat(bodies.get(1).at("/message/android/priority").asText()).isEqualTo("normal");
            assertThat(bodies.get(1).at("/message/apns/payload/aps/interruption-level").asText())
                    .isEqualTo("active");
            assertThat(bodies.get(2).at("/message/android/priority").asText()).isEqualTo("normal");
            assertThat(bodies.get(2).at("/message/apns/payload/aps/interruption-level").asText())
                    .isEqualTo("passive");
        } finally {
            transport.close();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("알람 설정 변경 data payload를 플랫폼 FCM payload에 전달한다")
    void 알람_설정_변경_data_payload를_전달한다() throws Exception {
        // given
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = payloadServer(mapper, bodies);
        FcmHttpTransport transport = new FcmHttpTransport(endpoint(server), mapper, () -> "loopback-access-token",
                Duration.ofSeconds(2));
        FcmSendDto message = FcmSendDto.builder()
                .title("복약 알람 변경")
                .content("복약 알람 설정이 변경되었습니다.")
                .data(Map.of("type", "MEDICATION_SCHEDULE_CHANGED", "revision", "42"))
                .build();
        try {
            // when
            FcmTransport.Result result = transport.send("loopback-token", message);

            // then
            assertThat(result.success()).isTrue();
            assertThat(bodies).hasSize(1);
            assertThat(bodies.get(0).at("/message/data/type").asText())
                    .isEqualTo("MEDICATION_SCHEDULE_CHANGED");
            assertThat(bodies.get(0).at("/message/data/revision").asText()).isEqualTo("42");
        } finally {
            transport.close();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("인증과 preflight 후 발송하면 잔여 TTL과 고정 notificationId를 플랫폼 payload에 전달한다")
    void 인증과_검증_대기를_차감한_TTL과_중복식별자를_전달한다() throws Exception {
        // given
        Instant start = Instant.parse("2026-09-14T00:00:00Z");
        MutableClock clock = new MutableClock(start);
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        HttpServer server = payloadServer(mapper, bodies);
        FcmHttpTransport transport = new FcmHttpTransport(endpoint(server), mapper, () -> {
            clock.advance(Duration.ofSeconds(30));
            return "loopback-access-token";
        }, Duration.ofSeconds(2), clock);
        FcmDelivery first = new FcmDelivery(608L, 1, "loopback-token", alarmChangedMessage(), start.plusSeconds(300));
        FcmDelivery retry = new FcmDelivery(608L, 2, "loopback-token", alarmChangedMessage(), first.expiresAt());
        try {
            // when
            assertThat(transport.send(first, () -> {
                clock.advance(Duration.ofSeconds(10));
                return true;
            }).success()).isTrue();
            clock.advance(Duration.ofSeconds(20));
            assertThat(transport.send(retry, () -> true).success()).isTrue();
            // then: 300 - OAuth 30 - preflight 10이며, 재시도는 최초 만료 시각과 ID를 유지한다.
            assertThat(bodies).hasSize(2);
            assertThat(bodies.get(0).at("/message/android/ttl").asText()).isEqualTo("260s");
            assertThat(bodies.get(1).at("/message/android/ttl").asText()).isEqualTo("210s");
            for (var body : bodies) {
                assertThat(body.at("/message/apns/headers/apns-expiration").asText())
                        .isEqualTo(Long.toString(first.expiresAt().getEpochSecond()));
                assertThat(body.at("/message/data/notificationId").isTextual()).isTrue();
                assertThat(body.at("/message/data/notificationId").asText()).isEqualTo("608");
                assertThat(body.at("/message/data/type").asText()).isEqualTo("MEDICATION_SCHEDULE_CHANGED");
                assertThat(body.at("/message/data/revision").asText()).isEqualTo("42");
                assertThat(body.at("/message/notification/title").asText()).isEqualTo("알림");
                assertThat(body.at("/message/token").asText()).isEqualTo("loopback-token");
            }
        } finally {
            transport.close();
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("인증 또는 preflight 대기 중 만료되면 HTTP 요청을 보내지 않는다")
    void 인증_또는_검증_중_만료는_HTTP를_차단한다(boolean expireInPreflight) throws Exception {
        // given
        Instant start = Instant.parse("2026-09-14T00:00:00Z");
        MutableClock clock = new MutableClock(start);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = countingServer(requests);
        FcmHttpTransport transport = new FcmHttpTransport(endpoint(server), new ObjectMapper(), () -> {
            if (!expireInPreflight) {
                clock.advance(Duration.ofSeconds(1));
            }
            return "loopback-access-token";
        }, Duration.ofSeconds(2), clock);
        FcmDelivery delivery = new FcmDelivery(608L, 1, "loopback-token", message(), start.plusSeconds(1));
        try {
            // when
            FcmTransport.Result result = transport.send(delivery, () -> {
                if (expireInPreflight) {
                    clock.advance(Duration.ofSeconds(1));
                }
                return true;
            });
            // then
            assertThat(result.success()).isFalse();
            assertThat(result.retryable()).isFalse();
            assertThat(result.permanentToken()).isFalse();
            assertThat(requests.get()).isZero();
        } finally {
            transport.close();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("잔여 TTL이 1초 미만이거나 28일을 넘으면 Android 보관 한도 안으로 내림한다")
    void 플랫폼_TTL의_초단위와_최대한도를_지킨다() throws Exception {
        // given
        Instant start = Instant.parse("2026-09-14T00:00:00Z");
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = payloadServer(mapper, bodies);
        FcmHttpTransport transport = new FcmHttpTransport(endpoint(server), mapper, () -> "loopback-access-token",
                Duration.ofSeconds(2), Clock.fixed(start, ZoneOffset.UTC));
        try {
            // when
            assertThat(transport.send(new FcmDelivery(1L, 1, "token", message(), start.plusMillis(900)), () -> true)
                    .success()).isTrue();
            assertThat(transport.send(new FcmDelivery(2L, 1, "token", message(), start.plus(Duration.ofDays(30))), () -> true)
                    .success()).isTrue();
            // then
            assertThat(bodies.get(0).at("/message/android/ttl").asText()).isEqualTo("0s");
            assertThat(bodies.get(0).at("/message/apns/headers/apns-expiration").asText())
                    .isEqualTo(Long.toString(start.getEpochSecond()));
            assertThat(bodies.get(1).at("/message/android/ttl").asText()).isEqualTo("2419200s");
        } finally {
            transport.close();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("발송 직전 자격이 없으면 FCM 네트워크를 호출하지 않는다")
    void 발송_직전_자격_거부는_네트워크를_차단한다() throws Exception {
        // given
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = countingServer(requests);
        FcmHttpTransport transport = transport(server, Duration.ofSeconds(2));
        try {
            // when
            FcmTransport.Result result = transport.send("test-token", message(), () -> false);
            // then
            assertThat(result.success()).isFalse();
            assertThat(result.retryable()).isFalse();
            assertThat(result.permanentToken()).isFalse();
            assertThat(requests.get()).isZero();
        } finally {
            transport.close();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("자격증명 갱신 중 자격이 변경되면 최신 자격으로 발송을 차단한다")
    void 자격증명_갱신_중_소유자_변경을_재확인한다() throws Exception {
        // given
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean eligible = new AtomicBoolean(true);
        CountDownLatch refreshing = new CountDownLatch(1);
        CountDownLatch refreshed = new CountDownLatch(1);
        HttpServer server = countingServer(requests);
        FcmHttpTransport transport = new FcmHttpTransport(endpoint(server), new ObjectMapper(), () -> {
            refreshing.countDown();
            try {
                if (!refreshed.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("Test credential refresh deadline");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            return "test-access-token";
        }, Duration.ofSeconds(3));
        var caller = Executors.newSingleThreadExecutor();
        try {
            // when
            var pending = caller.submit(() -> transport.send("test-token", message(), eligible::get));
            assertThat(refreshing.await(2, TimeUnit.SECONDS)).isTrue();
            eligible.set(false);
            refreshed.countDown();
            FcmTransport.Result result = pending.get(2, TimeUnit.SECONDS);
            // then
            assertThat(result.success()).isFalse();
            assertThat(result.retryable()).isFalse();
            assertThat(requests.get()).isZero();
        } finally {
            refreshed.countDown();
            caller.shutdownNow();
            transport.close();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("FCM이 200을 반환하면 HTTP 수락으로 판정한다")
    void 응답이_200이면_수락한다() throws Exception {
        // given
        HttpServer server = server(200, "{}", null, 0);
        FcmHttpTransport transport = transport(server, Duration.ofSeconds(2));
        try {
            // when
            FcmTransport.Result result = transport.send("test-token", message());
            // then
            assertThat(result.success()).isTrue();
        } finally {
            transport.close();
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    @DisplayName("FCM이 일시 오류를 반환하면 Retry-After 이후 재시도한다")
    void 일시_오류는_제공자_지연을_유지한다(int status) throws Exception {
        // given
        HttpServer server = server(status, "{}", "120", 0);
        FcmHttpTransport transport = transport(server, Duration.ofSeconds(2));
        try {
            // when
            FcmTransport.Result result = transport.send("test-token", message());
            // then
            assertThat(result.retryable()).isTrue();
            assertThat(result.permanentToken()).isFalse();
            assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(120));
        } finally {
            transport.close();
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNREGISTERED", "SENDER_ID_MISMATCH"})
    @DisplayName("FCM이 토큰 영구 오류를 반환하면 해당 토큰 무효화를 요청한다")
    void 명시적_토큰_오류만_무효화한다(String code) throws Exception {
        // given
        String body = "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"" + code + "\"}]}}";
        HttpServer server = server(400, body, null, 0);
        FcmHttpTransport transport = transport(server, Duration.ofSeconds(2));
        try {
            // when
            FcmTransport.Result result = transport.send("test-token", message());
            // then
            assertThat(result.permanentToken()).isTrue();
            assertThat(result.retryable()).isFalse();
        } finally {
            transport.close();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("일반 400 오류를 반환하면 토큰을 무효화하지 않는다")
    void 일반_요청_오류는_토큰을_유지한다() throws Exception {
        // given
        HttpServer server = server(400, "{\"error\":{\"message\":\"UNREGISTERED\",\"status\":\"INVALID_ARGUMENT\"}}", null, 0);
        FcmHttpTransport transport = transport(server, Duration.ofSeconds(2));
        try {
            // when
            FcmTransport.Result result = transport.send("test-token", message());
            // then
            assertThat(result.permanentToken()).isFalse();
            assertThat(result.retryable()).isFalse();
            assertThat(result.success()).isFalse();
        } finally {
            transport.close();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("응답 본문이 지연되면 총 시간제한 안에 재시도를 반환한다")
    void 느린_응답_본문은_시간제한으로_종료한다() throws Exception {
        // given
        HttpServer server = server(200, "{}", null, 800);
        FcmHttpTransport transport = transport(server, Duration.ofMillis(150));
        try {
            // when
            long start = System.nanoTime();
            FcmTransport.Result result = transport.send("test-token", message());
            // then
            assertThat(result.retryable()).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(700));
        } finally {
            transport.close();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("자격증명 갱신이 지연되면 시간제한 이후 FCM을 호출하지 않는다")
    void 느린_자격증명은_뒤늦은_발송을_막는다() throws Exception {
        // given
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { requests.incrementAndGet(); exchange.close(); });
        server.start();
        FcmHttpTransport transport = new FcmHttpTransport(endpoint(server), new ObjectMapper(), () -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
                // 취소를 무시하는 자격 증명 구현을 흉내 낸다.
            }
            return "test-access-token";
        }, Duration.ofMillis(80));
        try {
            // when
            FcmTransport.Result result = transport.send("test-token", message());
            Thread.sleep(150);
            // then
            assertThat(result.retryable()).isTrue();
            assertThat(requests.get()).isZero();
        } finally {
            transport.close();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("DB 트랜잭션 안에서 호출하면 네트워크 전에 예외가 발생한다")
    void 트랜잭션_내_외부_호출을_차단한다() {
        // given
        AtomicInteger credentials = new AtomicInteger();
        FcmHttpTransport transport = new FcmHttpTransport(URI.create("http://127.0.0.1:1"), new ObjectMapper(), () -> {
            credentials.incrementAndGet();
            return "test";
        }, Duration.ofSeconds(1));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            // when / then
            assertThatThrownBy(() -> transport.send("test-token", message())).isInstanceOf(IllegalStateException.class);
            assertThat(credentials.get()).isZero();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            transport.close();
        }
    }

    private static FcmSendDto message() {
        return FcmSendDto.builder().title("알림").content("본문").build();
    }

    private static FcmSendDto alarmChangedMessage() {
        return FcmSendDto.builder().title("알림").content("본문")
                .data(Map.of("type", "MEDICATION_SCHEDULE_CHANGED", "revision", "42"))
                .build();
    }

    private static HttpServer payloadServer(ObjectMapper mapper, List<JsonNode> bodies)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            bodies.add(mapper.readTree(exchange.getRequestBody()));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant start) { this.now = new AtomicReference<>(start); }
        private void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    private static HttpServer countingServer(AtomicInteger requests) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static FcmHttpTransport transport(HttpServer server, Duration timeout) {
        return new FcmHttpTransport(endpoint(server), new ObjectMapper(), () -> "test-access-token", timeout);
    }

    private static URI endpoint(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    private static HttpServer server(int status, String body, String retryAfter, long bodyDelay) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (retryAfter != null) {
                exchange.getResponseHeaders().add("Retry-After", retryAfter);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try {
                Thread.sleep(bodyDelay);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }
}
