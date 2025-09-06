package com.widyu.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oauth.apple")
public record AppleProperties(
        String clientId,
        String teamId,
        String keyId,
        String privateKey,
        String redirectUri
) {
}