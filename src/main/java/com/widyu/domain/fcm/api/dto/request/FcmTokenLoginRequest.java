package com.widyu.domain.fcm.api.dto.request;

public record FcmTokenLoginRequest(
        String token,
        String deviceInfo
) {
}
