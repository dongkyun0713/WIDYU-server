package com.widyu.fcm;

import java.time.Duration;

public enum RetentionClass {
    ROUTINE_90D(Duration.ofDays(90)),
    HEART_EMERGENCY_180D(Duration.ofDays(180)),
    SAFE_ZONE_90D(Duration.ofDays(90));

    private final Duration duration;

    RetentionClass(Duration duration) {
        this.duration = duration;
    }

    public Duration duration() {
        return duration;
    }
}
