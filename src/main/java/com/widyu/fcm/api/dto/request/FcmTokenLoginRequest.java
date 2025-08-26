package com.widyu.fcm.api.dto.request;

public record FcmTokenLoginRequest(
        String token,
        String deviceInfo
) {
}
