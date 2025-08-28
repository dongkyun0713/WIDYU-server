package com.widyu.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oauth.state")
public record OAuthProperties(
        int length,
        long ttl
) {
}
