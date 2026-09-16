package com.widyu.fcm.application;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.ByteArrayContent;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;

class BoundedGoogleHttpTransportTest {
    @Test
    @DisplayName("자격증명 HTTP 요청을 보내면 요청 본문과 응답을 보존한다")
    void 자격증명_갱신_본문을_전송한다() throws Exception {
        // given
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"access_token\":\"test\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            // when
            var request = new BoundedGoogleHttpTransport().createRequestFactory().buildPostRequest(
                    new GenericUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/token"),
                    ByteArrayContent.fromString("application/x-www-form-urlencoded", "grant_type=test"));
            var response = request.execute();
            // then
            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(response.parseAsString()).contains("access_token");
            assertThat(received.get()).isEqualTo("grant_type=test");
            response.disconnect();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("자격증명 서버 본문이 멈추면 3초 제한으로 IOException을 반환한다")
    void 자격증명_응답_본문_지연을_제한한다() throws Exception {
        // given
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/token", exchange -> {
            exchange.sendResponseHeaders(200, 10);
            try {
                Thread.sleep(4500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var request = new BoundedGoogleHttpTransport().createRequestFactory().buildGetRequest(
                    new GenericUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/token"));
            request.setNumberOfRetries(0);
            // when / then
            long start = System.nanoTime();
            assertThatThrownBy(request::execute).isInstanceOf(IOException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }
}
