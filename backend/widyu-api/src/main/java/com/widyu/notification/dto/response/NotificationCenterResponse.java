package com.widyu.notification.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record NotificationCenterResponse(
        List<NotificationEnvelope> items,
        String nextCursor,
        Map<String, Integer> unreadCounts,
        String snapshotRevision,
        LocalDateTime serverTime
) {
    public static NotificationCenterResponse of(List<NotificationEnvelope> items,
                                                String nextCursor,
                                                Map<String, Integer> unreadCounts,
                                                String snapshotRevision,
                                                LocalDateTime serverTime) {
        return new NotificationCenterResponse(
                List.copyOf(items), nextCursor, Map.copyOf(unreadCounts), snapshotRevision, serverTime);
    }
}
