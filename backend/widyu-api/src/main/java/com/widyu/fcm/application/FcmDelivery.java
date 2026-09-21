package com.widyu.fcm.application;

import com.widyu.fcm.FcmOutbox;
import com.widyu.fcm.dto.FcmSendDto;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

public record FcmDelivery(Long id, long fence, String token, FcmSendDto message, Instant expiresAt) {
    public static FcmDelivery from(FcmOutbox row) {
        return new FcmDelivery(row.getId(), row.getFence(), row.getMemberFcmToken().getToken(),
                new FcmSendDto(row.getTitle(), row.getBody(), row.getFcmCategory(), row.getScheme(),
                        row.getImage(), row.isEmergency(), row.getRelatedMemberId(), data(row),
                        row.getDecisionId()),
                row.getExpiresAt().atZone(ZoneId.systemDefault()).toInstant());
    }

    private static Map<String, String> data(FcmOutbox row) {
        if (row.getDataType() == null || row.getDataRevision() == null) {
            return Map.of();
        }
        return Map.of("type", row.getDataType(), "revision", row.getDataRevision().toString());
    }
}
