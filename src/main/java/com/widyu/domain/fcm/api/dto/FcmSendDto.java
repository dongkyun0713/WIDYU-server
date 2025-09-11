package com.widyu.domain.fcm.api.dto;

import lombok.Builder;

@Builder
public record FcmSendDto(
        String title,
        String body
) {
}
