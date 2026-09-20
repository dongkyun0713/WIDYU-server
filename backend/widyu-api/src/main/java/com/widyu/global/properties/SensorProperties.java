package com.widyu.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sensor")
public record SensorProperties(
        int maxPayloadBytes
) {}
