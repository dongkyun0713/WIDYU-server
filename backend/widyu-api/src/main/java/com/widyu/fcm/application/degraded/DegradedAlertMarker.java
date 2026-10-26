package com.widyu.fcm.application.degraded;

import com.widyu.fcm.FcmOutbox;
import com.widyu.fcm.NotificationPolicy;
import com.widyu.fcm.application.FcmDeliveryProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 시니어별 「저하 경로가 이미 알렸다」 표식(LLD-0086 5.3·5.5). 저하 경로가 긴급 TTL 동안 한 번만 적재하게 하고,
 * outbox worker가 같은 위급을 다시 보내지 않게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DegradedAlertMarker {

    static final String KEY_PREFIX = "fcm:degraded:alerted:";

    private final StringRedisTemplate redis;
    private final FcmDeliveryProperties delivery;
    private final MeterRegistry meterRegistry;

    /** 표식이 없을 때만 남기고 true를 돌려준다. Redis 예외는 호출자가 처리한다. */
    public boolean tryMark(Long seniorId) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(seniorId), "1", delivery.emergencyTtl()));
    }

    public void unmark(Long seniorId) {
        redis.delete(key(seniorId));
    }

    /** Redis를 읽지 못하면 표식이 없는 것으로 본다(fail-open). 정상 경로 알림을 막지 않기 위해서다. */
    public boolean isMarked(Long seniorId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(seniorId)));
        } catch (RuntimeException exception) {
            log.warn("저하 경로 표식 조회 실패, 정상 발송 진행: seniorId={}, type={}",
                    seniorId, exception.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 저하 경로가 이미 알린 시니어의 심박 위급 outbox 행이면 true다. 워치 도움 요청(SELF_HELP_REQUESTED)은
     * 같은 카테고리지만 다른 사건이라 막지 않는다.
     */
    public boolean alreadyAlerted(FcmOutbox row) {
        if (!row.isEmergency() || row.getNotificationPolicy() == null
                || !"HEART_RATE_EMERGENCY".equals(row.getNotificationPolicy().legacyDataType())
                || row.getRelatedMemberId() == null) {
            return false;
        }
        if (!isMarked(row.getRelatedMemberId())) {
            return false;
        }
        meterRegistry.counter("fcm.degraded.deduped").increment();
        log.info("저하 경로가 이미 알린 위급이라 outbox 발송 취소: id={}, seniorId={}",
                row.getId(), row.getRelatedMemberId());
        return true;
    }

    private static String key(Long seniorId) {
        return KEY_PREFIX + seniorId;
    }
}
