package com.widyu.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 위치 열람 통보의 운영값(LLD-0056 4절, ADR-0036 결정 4).
 *
 * @param immediateCooldownMin 즉시 통보에서 같은 보호자의 반복 조회를 한 건으로 합치는 시간(분).
 *                             법무 검토가 「건마다」를 요구하면 0으로 둔다.
 * @param digestCron           모아서 통보 다이제스트를 돌리는 cron 식.
 */
@ConfigurationProperties(prefix = "location-access")
public record LocationAccessProperties(
        int immediateCooldownMin,
        String digestCron
) {
}
