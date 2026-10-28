package com.widyu.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "medicine")
public record MedicineProperties(
        Api api
) {
    public record Api(
            String url,
            String serviceKey,
            Duration connectTimeout,
            Duration readTimeout,
            Duration batchReadTimeout
    ) {}
}
