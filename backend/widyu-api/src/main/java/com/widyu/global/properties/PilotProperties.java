package com.widyu.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 일본 현장실증(pilot) 전용 설정. 값 검증은 pilot 프로파일에서만 동작하는
 * {@code PilotProfileGuard}가 수행한다. 다른 프로파일에서는 미설정 기본값으로 바인딩되고
 * 검증하지 않으므로 일반 기동에 영향을 주지 않는다.
 */
@ConfigurationProperties(prefix = "pilot")
public record PilotProperties(
        boolean isolationConfirmed,
        String firebaseProjectId
) {
}
