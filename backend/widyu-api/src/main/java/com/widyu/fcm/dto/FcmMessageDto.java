package com.widyu.fcm.dto;

import lombok.Builder;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Android(String ttl, String priority, AndroidNotification notification) {
        public Android(String ttl) {
            this(ttl, null, null);
        }
    }

    public record AndroidNotification(@JsonProperty("channel_id") String channelId) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Apns(Map<String, String> headers, ApnsPayload payload) {
        public Apns(Map<String, String> headers) {
            this(headers, null);
        }
    }

    public record ApnsPayload(Aps aps) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Aps(
            @JsonProperty("interruption-level") String interruptionLevel,
            @JsonProperty("content-available") Integer contentAvailable
    ) {
        public Aps(String interruptionLevel) {
            this(interruptionLevel, null);
        }
    }

    @Builder
    public record Notification(
            String title,
            String body,
            String image
    ) {}
}
