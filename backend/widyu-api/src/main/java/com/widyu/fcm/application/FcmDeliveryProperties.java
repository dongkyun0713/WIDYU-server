package com.widyu.fcm.application;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
/** maxRetries is the number of additional retries; total attempts are 1 + maxRetries. No policy defaults. */
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
            throw new IllegalArgumentException("FCM lease must exceed HTTP deadline plus 5 seconds");
        }
    }

    public FcmDeliveryProperties {
        if (maxRetries < 0 || maxRetries == Integer.MAX_VALUE || normalTtl == null || emergencyTtl == null
                || lease == null || normalTtl.isNegative() || normalTtl.isZero()
                || emergencyTtl.isNegative() || emergencyTtl.isZero() || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("FCM retry count and positive TTL/lease are required");
        }
    }

    public Duration ttl(boolean emergency) {
        if (emergency) { return emergencyTtl; }
        return normalTtl;
    }
}
