package com.widyu.fcm.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * maxRetries는 추가 재시도 횟수이며 전체 시도는 1 + maxRetries다. 정책 값에는 기본값을 두지 않는다.
 * 설정이 빠지면 0으로 바인딩되지 않고 기동이 실패하도록 int가 아니라 Integer로 받는다.
 * poll-delay-ms는 @Scheduled 애너테이션 속성이라 여기서 읽을 수 없다.
 */
@ConfigurationProperties(prefix = "fcm.delivery")
public record FcmDeliveryProperties(
        Integer maxRetries,
        Duration normalTtl,
        Duration emergencyTtl,
        @DefaultValue("60s") Duration lease,
        @DefaultValue("30s") Duration adminTimeout) {

    public FcmDeliveryProperties {
        if (maxRetries == null || maxRetries < 0 || maxRetries == Integer.MAX_VALUE || normalTtl == null
                || emergencyTtl == null || lease == null || adminTimeout == null
                || normalTtl.isNegative() || normalTtl.isZero()
                || emergencyTtl.isNegative() || emergencyTtl.isZero()
                || lease.isNegative() || lease.isZero()
                || adminTimeout.isNegative() || adminTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "fcm.delivery.max-retries는 0 이상이어야 하고 normal-ttl, emergency-ttl, lease, admin-timeout은 모두 양수여야 합니다.");
        }
    }

    public Duration ttl(boolean emergency) {
        if (emergency) { return emergencyTtl; }
        return normalTtl;
    }
}
