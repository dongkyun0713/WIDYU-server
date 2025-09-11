package com.widyu.domain.fcm.api.dto.response;

import com.widyu.domain.fcm.domain.FcmNotification;
import java.time.LocalDateTime;

public record FcmNotificationResponse(
        Long id,
        String title,
        String body,
        boolean isRead,
        LocalDateTime createdAt
) {
    public static FcmNotificationResponse from(FcmNotification n) {
        return new FcmNotificationResponse(
                n.getId(),
                n.getTitle(),
                n.getBody(),
                n.isRead(),
                n.getCreatedAt()
        );
    }
}
