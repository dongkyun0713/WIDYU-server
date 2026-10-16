package com.widyu.fcm.application.degraded;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.fcm.application.FcmDeliveryProperties;
import com.widyu.fcm.application.FcmTransport;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 저하 경로 스트림을 소비해 DB 없이 FCM으로 보낸다(LLD-0086 5.4). ack 전까지 항목이 pending에 남으므로
 * 재시작·전송 실패는 lease가 지난 뒤 다시 가져와 처리한다. 토큰·페이로드는 로그에 남기지 않는다.
 */
@Slf4j
@Component
public class DegradedEmergencyAlertConsumer {

    static final String GROUP = "fcm-degraded";
    static final String RECONCILE_KEY = "fcm:degraded:reconcile";
    private static final int BATCH = 50;

    private final StringRedisTemplate redis;
    private final FcmTransport transport;
    private final FcmDegradedProperties properties;
    private final FcmDeliveryProperties delivery;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final String consumerName;
    private volatile boolean groupReady;

    public DegradedEmergencyAlertConsumer(StringRedisTemplate redis, FcmTransport transport,
            FcmDegradedProperties properties, FcmDeliveryProperties delivery, ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.redis = redis;
        this.transport = transport;
        this.properties = properties;
        this.delivery = delivery;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.consumerName = consumerName();
    }

    @Scheduled(fixedDelayString = "${fcm.degraded.poll-ms:1000}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        try {
            ensureGroup();
            StreamOperations<String, Object, Object> stream = redis.opsForStream();
            reclaimExpiredLeases(stream);
            List<MapRecord<String, Object, Object>> records = stream.read(Consumer.from(GROUP, consumerName),
                    StreamReadOptions.empty().count(BATCH),
                    StreamOffset.create(DegradedEmergencyAlertService.STREAM_KEY, ReadOffset.lastConsumed()));
            handleAll(stream, records);
        } catch (RuntimeException exception) {
            // 그룹이 사라졌을 수 있으니(Redis 초기화 등) 다음 주기에 다시 만든다.
            groupReady = false;
            log.warn("저하 경로 소비 실패: type={}", exception.getClass().getSimpleName());
        }
    }

    /** lease가 지난 pending 항목을 가져온다. 시도 횟수를 다 쓴 항목은 ack 후 버린다. */
    private void reclaimExpiredLeases(StreamOperations<String, Object, Object> stream) {
        int maxAttempts = 1 + delivery.maxRetries();
        for (PendingMessage pending : stream.pending(
                DegradedEmergencyAlertService.STREAM_KEY, GROUP, Range.unbounded(), BATCH)) {
            if (pending.getElapsedTimeSinceLastDelivery().compareTo(properties.lease()) < 0) {
                continue;
            }
            if (pending.getTotalDeliveryCount() >= maxAttempts) {
                stream.acknowledge(DegradedEmergencyAlertService.STREAM_KEY, GROUP, pending.getId());
                dropped("retries_exhausted");
                log.warn("저하 경로 재시도 소진: streamId={}", pending.getIdAsString());
                continue;
            }
            handleAll(stream, stream.claim(DegradedEmergencyAlertService.STREAM_KEY, GROUP, consumerName,
                    properties.lease(), pending.getId()));
        }
    }

    private void handleAll(StreamOperations<String, Object, Object> stream,
            List<MapRecord<String, Object, Object>> records) {
        if (records == null) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            try {
                handle(stream, record);
            } catch (RuntimeException exception) {
                // ack하지 않아 lease 뒤 다시 시도하고, 횟수를 다 쓰면 위에서 버린다.
                log.warn("저하 경로 발송 중단: streamId={}, type={}",
                        record.getId().getValue(), exception.getClass().getSimpleName());
            }
        }
    }

    private void handle(StreamOperations<String, Object, Object> stream, MapRecord<String, Object, Object> record) {
        DegradedDelivery item = DegradedDelivery.from(record);
        long now = System.currentTimeMillis();
        if (item.expired(now)) {
            ack(stream, record);
            dropped("expired");
            return;
        }
        FcmTransport.Result result = transport.send(item.toFcmDelivery(), () -> true);
        if (result.success()) {
            // 같은 알림을 두 번 보내지 않는 쪽을 우선해 ack 뒤 보정 기록을 남긴다.
            ack(stream, record);
            redis.opsForList().rightPush(RECONCILE_KEY, reconcileJson(item, now));
            meterRegistry.counter("fcm.degraded.sent").increment();
            return;
        }
        if (result.retryable()) {
            return;
        }
        ack(stream, record);
        if (result.permanentToken()) {
            // 토큰 비활성화는 DB가 없어 하지 않는다. 다음 스냅샷 갱신이 DB 기준으로 거른다.
            dropped("permanent_token");
            return;
        }
        dropped("rejected");
    }

    private void ack(StreamOperations<String, Object, Object> stream, MapRecord<String, Object, Object> record) {
        stream.acknowledge(DegradedEmergencyAlertService.STREAM_KEY, GROUP, record.getId());
    }

    private String reconcileJson(DegradedDelivery item, long sentAtMs) {
        try {
            return objectMapper.writeValueAsString(item.reconcileRecord(sentAtMs));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("저하 경로 보정 기록을 쓸 수 없습니다.", exception);
        }
    }

    private void ensureGroup() {
        if (groupReady) {
            return;
        }
        try {
            // 0부터 읽게 만들어 그룹보다 먼저 적재된 항목도 놓치지 않는다. 만료 항목은 handle이 버린다.
            redis.opsForStream().createGroup(DegradedEmergencyAlertService.STREAM_KEY, ReadOffset.from("0"), GROUP);
        } catch (RuntimeException exception) {
            String message = NestedExceptionUtils.getMostSpecificCause(exception).getMessage();
            if (message == null || !message.contains("BUSYGROUP")) {
                throw exception;
            }
        }
        groupReady = true;
    }

    private void dropped(String reason) {
        meterRegistry.counter("fcm.degraded.dropped", "reason", reason).increment();
    }

    private static String consumerName() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            return "widyu-api";
        }
        return host;
    }
}
