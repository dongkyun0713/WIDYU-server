package com.widyu.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oauth.naver")
public record NaverProperties(
    String clientId,
    String clientSecret,
    String redirectUri
) {
}
