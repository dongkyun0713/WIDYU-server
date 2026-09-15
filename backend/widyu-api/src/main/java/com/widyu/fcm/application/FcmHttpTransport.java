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
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

@Component
public class FcmHttpTransport implements FcmTransport {
    private final URI endpoint;
    private final ObjectMapper mapper;
    private final AccessTokenProvider credentials;
    private final Duration totalTimeout;
    private final Clock clock;
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
        this(endpoint, mapper, credentials, totalTimeout, Clock.systemUTC());
    }

    FcmHttpTransport(URI endpoint, ObjectMapper mapper, AccessTokenProvider credentials, Duration totalTimeout, Clock clock) {
        if (totalTimeout.isZero() || totalTimeout.isNegative()) {
            throw new IllegalArgumentException("firebase.http.total-timeout은 양수여야 합니다.");
        }
        this.endpoint = endpoint;
        this.mapper = mapper;
        this.credentials = credentials;
        this.totalTimeout = totalTimeout;
        this.clock = clock;
    }

    @Override
    public Result send(String token, FcmSendDto dto) {
        return send(token, dto, () -> true);
    }

    @Override
    public Result send(String token, FcmSendDto dto, BooleanSupplier beforeSend) {
        return send(token, dto, beforeSend, null, null);
    }

    @Override
    public Result send(FcmDelivery delivery, BooleanSupplier beforeSend) {
        return send(delivery.token(), delivery.message(), beforeSend, delivery.id(), delivery.expiresAt());
    }

    private Result send(String token, FcmSendDto dto, BooleanSupplier beforeSend, Long notificationId, Instant expiresAt) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("FCM HTTP 호출은 DB 트랜잭션 밖에서 실행해야 합니다.");
        }
        long deadline = System.nanoTime() + totalTimeout.toNanos();
        Future<Result> task = null;
        try {
            task = executor.submit(() -> execute(token, dto, beforeSend, deadline, notificationId, expiresAt));
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

    private Result execute(String token, FcmSendDto dto, BooleanSupplier beforeSend, long deadline,
            Long notificationId, Instant expiresAt) throws Exception {
        String accessToken = credentials.get();
        if (!beforeSend.getAsBoolean()) {
            return Result.rejected(false);
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
            return Result.retry(Duration.ZERO);
        }
        FcmMessageDto.Message.MessageBuilder message = FcmMessageDto.Message.builder().token(token)
                .notification(FcmMessageDto.Notification.builder().title(dto.title())
                        .body(dto.content()).image(dto.image()).build());
        if (expiresAt != null) {
            Instant now = clock.instant();
            if (!expiresAt.isAfter(now)) {
                return Result.rejected(false);
            }
            // FCM은 최대 28일까지 받고 Android TTL을 초 단위로 내림한다.
            long ttl = Math.min(Duration.between(now, expiresAt).getSeconds(), Duration.ofDays(28).getSeconds());
            message.data(Map.of("notificationId", notificationId.toString()))
                    .android(new FcmMessageDto.Android(ttl + "s"))
                    .apns(new FcmMessageDto.Apns(Map.of("apns-expiration", Long.toString(expiresAt.getEpochSecond()))));
        }
        FcmMessageDto body = FcmMessageDto.builder().validateOnly(false).message(message.build()).build();
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(remaining))
                .header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        if (expiresAt != null && !expiresAt.isAfter(clock.instant())) {
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
            // 형식이 깨진 오류 응답으로는 토큰을 무효화하지 않는다.
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
                // 제공자 지연 값이 없거나 잘못되면 영속화된 재시도 일정을 따른다.
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
