package com.widyu.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oauth.kakao")
public record KakaoProperties(
        String clientId,
        String redirectUri
) {
}
