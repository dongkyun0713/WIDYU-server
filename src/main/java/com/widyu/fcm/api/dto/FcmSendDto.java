package com.widyu.fcm.api.dto;

import lombok.Builder;

@Builder
public record FcmSendDto(
        String token,
        String title,
        String body
) {
}
