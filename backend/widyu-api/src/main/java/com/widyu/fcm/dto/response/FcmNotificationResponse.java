package com.widyu.fcm.dto.response;

import com.widyu.fcm.FcmNotification;
import java.time.LocalDateTime;

public record FcmNotificationResponse(
        Long notificationId,
        String image,
        String category,
        String title,
        String content,
        LocalDateTime createdAt,
        boolean isRead,
        String path
) {
    public static FcmNotificationResponse from(FcmNotification n) {
        String category = "ALL";
        if (n.getFcmCategory() != null) {
            category = n.getFcmCategory().name();
        }
        String path = "";
        if (n.getDeepLink() != null) {
            path = n.getDeepLink();
        }
        return new FcmNotificationResponse(
                n.getId(),
                n.getImage(),
                category,
                n.getTitle(),
                n.getBody(),
                n.getCreatedAt(),
                n.isRead(),
                path
        );
    }
}
