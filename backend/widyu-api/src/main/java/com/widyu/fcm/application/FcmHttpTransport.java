package com.widyu.fcm.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.widyu.fcm.dto.FcmMessageDto;
import com.widyu.fcm.dto.FcmSendDto;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

@Component
public class FcmHttpTransport implements FcmTransport {
    private final URI endpoint;
    private final ObjectMapper mapper;
    private final AccessTokenProvider credentials;
    private final Duration totalTimeout;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ExecutorService executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("fcm-http-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    @Autowired
    public FcmHttpTransport(FcmMessagingUrl url, ObjectMapper mapper, ResourceLoader loader,
            @Value("${firebase.config-path}") String path,
            @Value("${firebase.http.total-timeout:10s}") Duration totalTimeout) {
        this(url.value(), mapper, () -> {
            try (var stream = loader.getResource(path).getInputStream()) {
                GoogleCredentials google = GoogleCredentials.fromStream(stream, BoundedGoogleHttpTransport::new)
                        .createScoped(List.of("https://www.googleapis.com/auth/firebase.messaging"));
                google.refreshIfExpired();
                return google.getAccessToken().getTokenValue();
            }
        }, totalTimeout);
    }

    FcmHttpTransport(URI endpoint, ObjectMapper mapper, AccessTokenProvider credentials, Duration totalTimeout) {
        if (totalTimeout.isZero() || totalTimeout.isNegative()) {
            throw new IllegalArgumentException("FCM total timeout must be positive");
        }
        this.endpoint = endpoint;
        this.mapper = mapper;
        this.credentials = credentials;
        this.totalTimeout = totalTimeout;
    }

    @Override
    public Result send(String token, FcmSendDto dto) {
        return send(token, dto, () -> true);
    }

    @Override
    public Result send(String token, FcmSendDto dto, BooleanSupplier beforeSend) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("FCM HTTP must run outside a database transaction");
        }
        long deadline = System.nanoTime() + totalTimeout.toNanos();
        Future<Result> task = null;
        try {
            task = executor.submit(() -> execute(token, dto, beforeSend, deadline));
            return task.get(totalTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Result.retry(Duration.ZERO);
        } catch (ExecutionException | TimeoutException | RejectedExecutionException exception) {
            return Result.retry(Duration.ZERO);
        } finally {
            if (task != null && !task.isDone()) {
                task.cancel(true);
            }
        }
    }

    private Result execute(String token, FcmSendDto dto, BooleanSupplier beforeSend, long deadline) throws Exception {
        String accessToken = credentials.get();
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
            return Result.retry(Duration.ZERO);
        }
        FcmMessageDto body = FcmMessageDto.builder().validateOnly(false)
                .message(FcmMessageDto.Message.builder().token(token)
                        .notification(FcmMessageDto.Notification.builder().title(dto.title())
                                .body(dto.content()).image(dto.image()).build()).build()).build();
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(remaining))
                .header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        if (!beforeSend.getAsBoolean()) {
            return Result.rejected(false);
        }
        if (deadline - System.nanoTime() <= 0 || Thread.currentThread().isInterrupted()) {
            return Result.retry(Duration.ZERO);
        }
        CompletableFuture<HttpResponse<String>> pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response;
        try {
            response = pending.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } finally {
            if (!pending.isDone()) {
                pending.cancel(true);
            }
        }
        if (response.statusCode() == 200) {
            return Result.delivered();
        }
        if (response.statusCode() == 429 || response.statusCode() >= 500) {
            return Result.retry(retryAfter(response.headers().firstValue("Retry-After").orElse("")));
        }
        return Result.rejected(permanentToken(response.body()));
    }

    private boolean permanentToken(String body) {
        try {
            JsonNode details = mapper.readTree(body).path("error").path("details");
            for (JsonNode detail : details) {
                if (!"type.googleapis.com/google.firebase.fcm.v1.FcmError".equals(detail.path("@type").asText())) {
                    continue;
                }
                String code = detail.path("errorCode").asText();
                if ("UNREGISTERED".equals(code) || "SENDER_ID_MISMATCH".equals(code)) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            // Malformed errors never authorize token invalidation.
        }
        return false;
    }

    static Duration retryAfter(String value) {
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value)));
        } catch (NumberFormatException ignored) {
            try {
                Duration delay = Duration.between(ZonedDateTime.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME));
                if (!delay.isNegative()) {
                    return delay;
                }
            } catch (RuntimeException invalidDate) {
                // Missing/invalid provider delay uses the persisted retry schedule.
            }
            return Duration.ZERO;
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
        client.shutdownNow();
    }

    @FunctionalInterface
    interface AccessTokenProvider { String get() throws IOException; }
}
