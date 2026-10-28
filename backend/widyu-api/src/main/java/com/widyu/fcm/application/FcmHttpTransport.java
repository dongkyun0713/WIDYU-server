package com.widyu.fcm.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.google.auth.oauth2.GoogleCredentials;
import com.widyu.fcm.dto.FcmMessageDto;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.DeliveryMode;
import com.widyu.fcm.NotificationType;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.HashMap;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

@Component
public class FcmHttpTransport implements FcmTransport {
    private static final String FCM_ERROR_TYPE = "type.googleapis.com/google.firebase.fcm.v1.FcmError";
    private static final String APNS_ERROR_TYPE = "type.googleapis.com/google.firebase.fcm.v1.ApnsError";
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

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
            FirebaseProperties properties) {
        this(url.value(), mapper, () -> {
            try (var stream = loader.getResource(properties.configPath()).getInputStream()) {
                GoogleCredentials google = GoogleCredentials.fromStream(stream, BoundedGoogleHttpTransport::new)
                        .createScoped(List.of("https://www.googleapis.com/auth/firebase.messaging"));
                google.refreshIfExpired();
                return google.getAccessToken().getTokenValue();
            }
        }, properties.http().totalTimeout());
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
        return send(delivery.token(), delivery.message(), beforeSend,
                delivery.notificationId(), delivery.expiresAt());
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
            return Result.rejected(false, Result.PREFLIGHT);
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
            return Result.retry(Duration.ZERO);
        }
        FcmMessageDto.Message.MessageBuilder message = FcmMessageDto.Message.builder().token(token)
                .notification(FcmMessageDto.Notification.builder().title(dto.title())
                        .body(dto.content()).image(dto.image()).build());
        NotificationType type = dto.notificationType();
        if (type != null && type.deliveryMode() == DeliveryMode.DATA_ONLY) {
            message.notification(null);
        }
        HashMap<String, String> data = new HashMap<>(dto.data());
        // 배포 전에 저장된 outbox 행의 옛 type 키를 notificationType으로 옮긴다(LLD-0109).
        String legacyType = data.remove("type");
        if (legacyType != null) {
            data.putIfAbsent("notificationType", legacyType);
        }
        if (notificationId != null) {
            data.put("notificationId", notificationId.toString());
        } else {
            data.remove("notificationId");
        }
        addTypeData(data, type);
        if (type != null || !data.isEmpty()) {
            message.data(data);
        }
        if (expiresAt != null) {
            Instant now = clock.instant();
            if (!expiresAt.isAfter(now)) {
                return Result.rejected(false, Result.EXPIRED_BEFORE_SEND);
            }
            // FCM은 최대 28일까지 받고 Android TTL을 초 단위로 내림한다.
            long ttl = Math.min(Duration.between(now, expiresAt).getSeconds(), Duration.ofDays(28).getSeconds());
            if (type == null) {
                message.android(new FcmMessageDto.Android(ttl + "s"))
                        .apns(new FcmMessageDto.Apns(Map.of("apns-expiration", Long.toString(expiresAt.getEpochSecond()))));
            } else {
                message.android(android(ttl + "s", type))
                        .apns(apns(expiresAt, type));
            }
        }
        if (expiresAt == null && type != null) {
            message.android(android(null, type)).apns(apns(null, type));
        }
        FcmMessageDto body = FcmMessageDto.builder().validateOnly(false).message(message.build()).build();
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(remaining))
                .header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        if (expiresAt != null && !expiresAt.isAfter(clock.instant())) {
            return Result.rejected(false, Result.EXPIRED_BEFORE_SEND);
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
        JsonNode error = errorOf(response.body());
        return Result.rejected(permanentToken(error), rejectionDetail(response.statusCode(), error));
    }

    private void addTypeData(Map<String, String> data, NotificationType type) {
        if (type == null) {
            return;
        }
        data.put("notificationType", type.name());
        data.putIfAbsent("deepLink", "");
    }

    private FcmMessageDto.Android android(String ttl, NotificationType type) {
        // 앱을 바로 깨워야 하는 데이터 전용 신호(W03)는 표시 알림 없이 안전 채널·HIGH로 보낸다.
        if (type.deliveryMode() == DeliveryMode.DATA_ONLY && type.priority().isSafety()) {
            return new FcmMessageDto.Android(ttl, "HIGH", new FcmMessageDto.AndroidNotification("widyu_safety"));
        }
        if (type.deliveryMode() == DeliveryMode.DATA_ONLY) {
            return new FcmMessageDto.Android(ttl);
        }
        // FCM HTTP v1 AndroidMessagePriority 표기(HIGH/NORMAL)를 그대로 쓴다. 급한 알림만 HIGH로 즉시 깨운다.
        if (type.priority().isSafety()) {
            return new FcmMessageDto.Android(ttl, "HIGH", new FcmMessageDto.AndroidNotification("widyu_safety"));
        }
        if (type.priority().isTimeSensitive()) {
            return new FcmMessageDto.Android(ttl, "HIGH", new FcmMessageDto.AndroidNotification("widyu_general"));
        }
        return new FcmMessageDto.Android(ttl, "NORMAL", new FcmMessageDto.AndroidNotification("widyu_general"));
    }

    private FcmMessageDto.Apns apns(Instant expiresAt, NotificationType type) {
        HashMap<String, String> headers = new HashMap<>();
        if (expiresAt != null) {
            headers.put("apns-expiration", Long.toString(expiresAt.getEpochSecond()));
        }
        if (type.deliveryMode() == DeliveryMode.DATA_ONLY && type.priority().isSafety()) {
            headers.put("apns-priority", "10");
            headers.put("apns-push-type", "alert");
            return new FcmMessageDto.Apns(headers,
                    new FcmMessageDto.ApnsPayload(new FcmMessageDto.Aps("active", null, "default")));
        }
        if (type.deliveryMode() == DeliveryMode.DATA_ONLY) {
            headers.put("apns-priority", "5");
            headers.put("apns-push-type", "background");
            return new FcmMessageDto.Apns(headers,
                    new FcmMessageDto.ApnsPayload(new FcmMessageDto.Aps(null, 1)));
        }
        // Android HIGH와 같은 급한 알림만 즉시(10), 나머지는 전력 상황을 고려해 보내도 되는 5로 보낸다.
        String apnsPriority = "5";
        if (type.priority().isTimeSensitive()) {
            apnsPriority = "10";
        }
        headers.put("apns-priority", apnsPriority);
        headers.put("apns-push-type", "alert");
        return new FcmMessageDto.Apns(headers,
                new FcmMessageDto.ApnsPayload(new FcmMessageDto.Aps(type.priority().interruptionLevel())));
    }

    private JsonNode errorOf(String body) {
        try {
            return mapper.readTree(body).path("error");
        } catch (IOException ignored) {
            // 형식이 깨진 오류 응답으로는 토큰을 무효화하지 않고 상태 코드만 남긴다.
            return MissingNode.getInstance();
        }
    }

    private static boolean permanentToken(JsonNode error) {
        for (JsonNode detail : error.path("details")) {
            if (!FCM_ERROR_TYPE.equals(detail.path("@type").asText())) {
                continue;
            }
            String code = detail.path("errorCode").asText();
            if ("UNREGISTERED".equals(code) || "SENDER_ID_MISMATCH".equals(code)) {
                return true;
            }
        }
        return false;
    }

    /** 예: {@code HTTP_401 UNAUTHENTICATED THIRD_PARTY_AUTH_ERROR}. 오류 메시지 원문은 토큰을 담을 수 있어 남기지 않는다. */
    private static String rejectionDetail(int statusCode, JsonNode error) {
        StringBuilder detail = new StringBuilder("HTTP_").append(statusCode);
        appendCode(detail, error.path("status").asText());
        for (JsonNode item : error.path("details")) {
            String type = item.path("@type").asText();
            if (FCM_ERROR_TYPE.equals(type)) {
                appendCode(detail, item.path("errorCode").asText());
            } else if (APNS_ERROR_TYPE.equals(type)) {
                appendCode(detail, item.path("reason").asText());
            }
        }
        return detail.toString();
    }

    private static void appendCode(StringBuilder detail, String code) {
        if (ERROR_CODE.matcher(code).matches()) {
            detail.append(' ').append(code);
        }
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
