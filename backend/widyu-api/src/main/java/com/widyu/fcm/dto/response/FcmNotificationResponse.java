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
        String scheme
) {
    public static FcmNotificationResponse from(FcmNotification n) {
        String category = "ALL";
        if (n.getFcmCategory() != null) {
            category = n.getFcmCategory().name();
        }
        String scheme = "";
        if (n.getDeepLink() != null) {
            scheme = n.getDeepLink();
        }
        return new FcmNotificationResponse(
                n.getId(),
                n.getImage(),
                category,
                n.getTitle(),
                n.getBody(),
                n.getCreatedAt(),
                scheme
        );
    }
}
