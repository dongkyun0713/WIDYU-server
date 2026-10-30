package com.widyu.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "juso.api")
public record JusoProperties(
        Duration connectTimeout,
        Duration readTimeout
) {}
