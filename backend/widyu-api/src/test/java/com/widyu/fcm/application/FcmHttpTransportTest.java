package com.widyu.fcm.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.widyu.fcm.dto.FcmSendDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class FcmHttpTransportTest {
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
                    throw new java.io.IOException("Test credential refresh deadline");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(exception);
            }
            return "test-access-token";
        }, Duration.ofSeconds(3));
        var caller = java.util.concurrent.Executors.newSingleThreadExecutor();
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
                // Simulate a credential implementation that ignores cancellation.
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
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
