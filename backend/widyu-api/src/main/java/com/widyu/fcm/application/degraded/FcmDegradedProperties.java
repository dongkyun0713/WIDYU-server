package com.widyu.fcm.application.degraded;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * DB 장애 중 심박 긴급 알림 저하 경로 설정(LLD-0086 3절). 재시도 상한과 긴급 TTL은 {@code fcm.delivery}를 그대로 쓴다.
 * reconcile-batch는 보정 한 주기에 옮길 최대 건수다(LLD-0087 3절).
 * snapshot-refresh-ms·poll-ms·reconcile-ms는 @Scheduled 애너테이션 속성이라 여기서 읽지 않는다.
 */
@ConfigurationProperties(prefix = "fcm.degraded")
public record FcmDegradedProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("24h") Duration snapshotTtl,
        @DefaultValue("60s") Duration lease,
        @DefaultValue("10000") long streamMaxLen,
        @DefaultValue("100") int reconcileBatch) {

    public FcmDegradedProperties {
        if (snapshotTtl == null || lease == null
                || snapshotTtl.isNegative() || snapshotTtl.isZero()
                || lease.isNegative() || lease.isZero()
                || streamMaxLen <= 0 || reconcileBatch <= 0) {
            throw new IllegalArgumentException(
                    "fcm.degraded.snapshot-ttl, lease, stream-max-len, reconcile-batch는 모두 양수여야 합니다.");
        }
    }
}
