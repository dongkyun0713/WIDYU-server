package com.widyu.fcm.application.degraded;

import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.NotificationPolicy;
import com.widyu.fcm.application.FcmDeliveryProperties;
import com.widyu.fcm.dto.NotificationCopy;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** DB 장애 중 위급 판정을 Redis Stream에 적재한다(LLD-0086 5.3). 요청을 실패시키지 않도록 예외를 밖으로 내지 않는다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DegradedEmergencyAlertService {

    public static final String STREAM_KEY = "fcm:degraded:stream";
    /** 정상 경로 최초 보호자 알림(IncidentEscalation)과 같은 문구 코드다. */
    static final String COPY_CODE = "S04";

    private final DegradedAlertMarker marker;
    private final EmergencyRecipientSnapshotService snapshots;
    private final StringRedisTemplate redis;
    private final FcmDegradedProperties properties;
    private final FcmDeliveryProperties delivery;
    private final MeterRegistry meterRegistry;

    public boolean enabled() {
        return properties.enabled();
    }

    public void alertWithoutDatabase(Long seniorId, LocalDateTime measuredAt) {
        try {
            if (!marker.tryMark(seniorId)) {
                skipped("throttled");
                return;
            }
            EmergencyRecipientSnapshot snapshot = snapshots.find(seniorId).orElse(null);
            if (snapshot == null || snapshot.recipients().isEmpty()) {
                // 스냅샷이 생기면 다음 샘플이 다시 시도하도록 표식을 남기지 않는다.
                marker.unmark(seniorId);
                skipped("no_snapshot");
                log.warn("저하 경로 수신자 스냅샷 없음: seniorId={}", seniorId);
                return;
            }
            NotificationCopy copy = NotificationCopy.of(
                    NotificationPolicy.safety("S01/S03/S04/S06", true), COPY_CODE, copyValues(snapshot.seniorName()));
            String expiresAt = Long.toString(System.currentTimeMillis() + delivery.emergencyTtl().toMillis());
            for (EmergencyRecipientSnapshot.Recipient recipient : snapshot.recipients()) {
                Map<String, String> fields = new HashMap<>();
                fields.put("seniorId", seniorId.toString());
                fields.put("guardianId", recipient.guardianId().toString());
                fields.put("tokenId", recipient.tokenId().toString());
                fields.put("token", recipient.token());
                fields.put("title", copy.title());
                fields.put("body", copy.body());
                fields.put("image", nullToEmpty(snapshot.image()));
                fields.put("category", FcmCategory.HEART_MESSAGE.name());
                fields.put("measuredAt", measuredAt.toString());
                fields.put("expiresAt", expiresAt);
                redis.opsForStream().add(StreamRecords.mapBacked(fields).withStreamKey(STREAM_KEY));
            }
            // Spring Data Redis 3.3의 StreamOperations에는 XADD MAXLEN 옵션이 없어 적재 직후 근사 trim으로 상한을 건다.
            redis.opsForStream().trim(STREAM_KEY, properties.streamMaxLen(), true);
            meterRegistry.counter("fcm.degraded.published").increment(snapshot.recipients().size());
            log.warn("저하 경로 위급 적재: seniorId={}, recipients={}", seniorId, snapshot.recipients().size());
        } catch (RuntimeException exception) {
            skipped("redis_error");
            log.warn("저하 경로 위급 적재 실패: seniorId={}, type={}", seniorId, exception.getClass().getSimpleName());
        }
    }

    private void skipped(String reason) {
        meterRegistry.counter("fcm.degraded.skipped", "reason", reason).increment();
    }

    private static Map<String, String> copyValues(String seniorName) {
        if (seniorName == null) {
            return Map.of();
        }
        return Map.of("시니어 이름", seniorName);
    }

    private static String nullToEmpty(String value) {
        if (value == null) {
            return "";
        }
        return value;
    }
}
