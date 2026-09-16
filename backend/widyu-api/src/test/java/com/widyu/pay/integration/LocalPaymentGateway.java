package com.widyu.pay.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.widyu.pay.infrastructure.PaymentClient;
import feign.Client;
import feign.Feign;
import feign.Request;
import feign.Retryer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Stateful loopback PG: HTTP attempts and monetary operations are counted separately. */
final class LocalPaymentGateway implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Map<String, Object>> payments = new HashMap<>();
    private final Map<String, Map<String, Object>> results = new HashMap<>();
    private final Map<String, String> bodies = new HashMap<>();
    private final List<String> postKeys = new ArrayList<>();
    private volatile CountDownLatch overlap;
    private volatile boolean loseResponse;
    private int lookups;

    LocalPaymentGateway() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/payments", this::handle);
        server.start();
    }

    PaymentClient client() {
        Client transport = new Client.Default(null, null);
        return Feign.builder()
                .contract(new SpringMvcContract())
                .retryer(Retryer.NEVER_RETRY)
                .options(new Request.Options(2, TimeUnit.SECONDS, 15, TimeUnit.SECONDS, false))
                .client((request, options) -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .as("PG HTTP must run outside the caller DB transaction").isFalse();
                    return transport.execute(request, options);
                })
                .encoder((value, type, template) -> {
                    try {
                        template.body(mapper.writeValueAsString(value));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .decoder((response, type) -> mapper.readValue(response.body().asInputStream(),
                        mapper.constructType(type)))
                .target(PaymentClient.class, "http://127.0.0.1:" + server.getAddress().getPort() + "/payments");
    }

    void overlapNextTwoPosts() { overlap = new CountDownLatch(2); }
    void loseNextResponse() { loseResponse = true; }
    synchronized int operations() { return results.size(); }
    synchronized int lookups() { return lookups; }
    synchronized List<String> postKeys() { return List.copyOf(postKeys); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            Map<String, Object> response;
            boolean drop = false;
            boolean post = exchange.getRequestMethod().equals("POST");
            synchronized (this) {
                String path = exchange.getRequestURI().getPath();
                if (post) {
                    String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (key == null || key.isBlank()) {
                        throw new IllegalStateException("Missing PG idempotency header");
                    }
                    postKeys.add(key);
                    String signature = path + body;
                    if (bodies.containsKey(key) && !bodies.get(key).equals(signature)) {
                        throw new IllegalStateException("Idempotency key reused with a different request");
                    }
                    response = results.get(key);
                    if (response == null) {
                        JsonNode input = mapper.readTree(body);
                        if (path.endsWith("/confirm")) {
                            response = new HashMap<>();
                            response.put("paymentKey", input.get("paymentKey").asText());
                            response.put("orderId", input.get("orderId").asText());
                            response.put("orderName", "synthetic payment");
                            response.put("totalAmount", input.get("amount").asInt());
                            response.put("balanceAmount", input.get("amount").asInt());
                            response.put("status", "DONE");
                            response.put("requestedAt", "2026-09-14T00:00:00Z");
                            response.put("approvedAt", "2026-09-14T00:00:01Z");
                        } else {
                            String paymentKey = path.split("/")[2];
                            response = new HashMap<>(payments.get(paymentKey));
                            int balance = (int) response.get("balanceAmount") - input.get("cancelAmount").asInt();
                            response.put("balanceAmount", balance);
                            response.put("status", "PARTIAL_CANCELED");
                            if (balance == 0) {
                                response.put("status", "CANCELED");
                            }
                        }
                        results.put(key, response);
                        bodies.put(key, signature);
                        payments.put((String) response.get("paymentKey"), response);
                    }
                    drop = loseResponse;
                    loseResponse = false;
                } else {
                    lookups++;
                    response = payments.get(path.substring("/payments/".length()));
                }
            }
            CountDownLatch barrier = overlap;
            if (post && barrier != null) {
                barrier.countDown();
                if (!barrier.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Two PG requests did not overlap");
                }
            }
            if (drop) {
                // A valid status followed by a truncated body models a processed request with lost response.
                exchange.sendResponseHeaders(200, 1024);
                exchange.getResponseBody().write('{');
                return;
            }
            byte[] bytes = mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
