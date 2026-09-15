package com.widyu.fcm.application;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
/** maxRetries는 추가 재시도 횟수이며 전체 시도는 1 + maxRetries다. 정책 값에는 기본값을 두지 않는다. */
public record FcmDeliveryProperties(int maxRetries, Duration normalTtl, Duration emergencyTtl, Duration lease) {
    @Autowired
    public FcmDeliveryProperties(
            @Value("${fcm.delivery.max-retries}") int maxRetries,
            @Value("${fcm.delivery.normal-ttl}") Duration normalTtl,
            @Value("${fcm.delivery.emergency-ttl}") Duration emergencyTtl,
            @Value("${fcm.delivery.lease:60s}") Duration lease,
            @Value("${firebase.http.total-timeout:10s}") Duration httpTimeout) {
        this(maxRetries, normalTtl, emergencyTtl, lease);
        if (lease.compareTo(httpTimeout.plusSeconds(5)) <= 0) {
            throw new IllegalArgumentException("fcm.delivery.lease는 firebase.http.total-timeout보다 5초 넘게 커야 합니다.");
        }
    }

    public FcmDeliveryProperties {
        if (maxRetries < 0 || maxRetries == Integer.MAX_VALUE || normalTtl == null || emergencyTtl == null
                || lease == null || normalTtl.isNegative() || normalTtl.isZero()
                || emergencyTtl.isNegative() || emergencyTtl.isZero() || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("fcm.delivery.max-retries는 0 이상이어야 하고 normal-ttl, emergency-ttl, lease는 모두 양수여야 합니다.");
        }
    }

    public Duration ttl(boolean emergency) {
        if (emergency) { return emergencyTtl; }
        return normalTtl;
    }
}
