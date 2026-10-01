package com.widyu.notification.dto.response;

import com.widyu.fcm.FcmNotification;
import java.time.LocalDateTime;

public record NotificationReadResponse(Long notificationId, LocalDateTime readAt) {
    public static NotificationReadResponse from(FcmNotification notification) {
        return new NotificationReadResponse(notification.getId(), notification.getReadAt());
    }
}
