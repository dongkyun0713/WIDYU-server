package com.widyu.fcm.dto;

import lombok.Builder;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@Builder
public record FcmMessageDto(
        boolean validateOnly,
        Message message
) {

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Message(
            Notification notification,
            String token,
            Map<String, String> data,
            Android android,
            Apns apns
    ) {}

    public record Android(String ttl) {}

    public record Apns(Map<String, String> headers) {}

    @Builder
    public record Notification(
            String title,
            String body,
            String image
    ) {}
}
