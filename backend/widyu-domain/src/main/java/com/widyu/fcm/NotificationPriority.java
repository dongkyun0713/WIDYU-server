package com.widyu.fcm;

public enum NotificationPriority {
    CRITICAL("critical"),
    TIME_SENSITIVE("timeSensitive"),
    INTERACTION("interaction"),
    PASSIVE("passive");

    private final String wireValue;

    NotificationPriority(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public boolean isSafety() {
        return this == CRITICAL;
    }

    public boolean isTimeSensitive() {
        return this == CRITICAL || this == TIME_SENSITIVE;
    }

    public String interruptionLevel() {
        if (isTimeSensitive()) {
            return "time-sensitive";
        }
        if (this == PASSIVE) {
            return "passive";
        }
        return "active";
    }
}
