package com.widyu.fcm.api.dto;

import lombok.Builder;

@Builder
public record FcmMessageDto(
        boolean validateOnly,
        Message message
) {

    @Builder
    public record Message(
            Notification notification,
            String token
    ) {}

    @Builder
    public record Notification(
            String title,
            String body,
            String image
    ) {}
}
