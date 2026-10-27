package com.widyu.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 커스텀 {@code RedisConnectionFactory}가 Boot 자동 구성을 대신하므로, 연결에 쓰는 값은 모두 여기서 읽는다.
 * {@code timeout}은 Lettuce 명령 응답 대기 상한이다(ADR-0040 결정 7, LLD-0106).
 */
@ConfigurationProperties(prefix = "spring.data.redis")
public record RedisProperties(
        String host,
        int port,
        String password,
        boolean sslEnabled,
        @DefaultValue("3s") Duration timeout
) {
}
