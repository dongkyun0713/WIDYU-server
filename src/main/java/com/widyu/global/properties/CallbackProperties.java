package com.widyu.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oauth.callback")
public record CallbackProperties(
        String packageName,
        Schemes schemes
) {
    public record Schemes(
            String apple,
            String google,
            String kakao
    ) {
    }
}