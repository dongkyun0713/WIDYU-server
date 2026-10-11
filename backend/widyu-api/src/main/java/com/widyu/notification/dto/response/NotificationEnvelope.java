package com.widyu.notification.dto.response;

import com.widyu.fcm.DeliveryMode;
import com.widyu.fcm.FcmNotification;
import com.widyu.fcm.NotificationType;
import com.widyu.fcm.NotificationPriority;
import com.widyu.notification.NotificationCenterFilter;
import java.time.LocalDateTime;

public record NotificationEnvelope(
        Long notificationId,
        String eventId,
        String type,
        String category,
        String priority,
        String title,
        String body,
        String imageUrl,
        LocalDateTime occurredAt,
        LocalDateTime readAt,
        String deepLink,
        String entityId,
        Long seniorId,
        String actorDisplayName,
        String seniorDisplayName,
        Integer remainingLockedCount,
        LocalDateTime expiresAt,
        String retentionClass,
        String retentionPolicyVersion,
        boolean centerStored,
        Boolean pushEligible,
        String foregroundPresentation
) {
    public static NotificationEnvelope from(FcmNotification notification) {
        NotificationType type = notification.getType();
        if (type == null) {
            return new NotificationEnvelope(
                    notification.getId(),
                    "legacy:" + notification.getId(),
                    "LEGACY",
                    NotificationCenterFilter.categoryOfLegacy(notification.getFcmCategory()),
                    NotificationPriority.INTERACTION.wireValue(),
                    notification.getTitle(),
                    notification.getBody(),
                    notification.getImage(),
                    notification.getCreatedAt(),
                    notification.getReadAt(),
                    nullableLink(notification.getDeepLink()),
                    notification.getEntityId(),
                    notification.getSeniorId(),
                    notification.getActorDisplayName(),
                    notification.getSeniorDisplayName(),
                    notification.getRemainingLockedCount(),
                    notification.getExpiresAt(),
                    null,
                    notification.getRetentionPolicyVersion(),
                    true,
                    notification.getPushEligible(),
                    "BANNER"
            );
        }

        return new NotificationEnvelope(
                notification.getId(),
                notification.getEventId(),
                type.name(),
                type.centerFilter(),
                type.priority().wireValue(),
                notification.getTitle(),
                notification.getBody(),
                notification.getImage(),
                notification.getCreatedAt(),
                notification.getReadAt(),
                nullableLink(notification.getDeepLink()),
                notification.getEntityId(),
                notification.getSeniorId(),
                notification.getActorDisplayName(),
                notification.getSeniorDisplayName(),
                notification.getRemainingLockedCount(),
                notification.getExpiresAt(),
                type.retentionClass().name(),
                notification.getRetentionPolicyVersion(),
                storesInCenter(type.deliveryMode()),
                notification.getPushEligible(),
                type.foregroundPresentation()
        );
    }

    private static boolean storesInCenter(DeliveryMode mode) {
        return mode == DeliveryMode.PUSH_AND_CENTER || mode == DeliveryMode.CENTER_ONLY;
    }

    private static String nullableLink(String deepLink) {
        if (deepLink == null || deepLink.isBlank()) {
            return null;
        }
        return deepLink;
    }
}
