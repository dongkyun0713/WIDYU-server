package com.widyu.fcm.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.fcm.FcmOutbox;
import com.widyu.fcm.DeliveryMode;
import com.widyu.fcm.dto.FcmSendDto;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

public record FcmDelivery(Long id, long fence, String token, FcmSendDto message, Instant expiresAt,
        Long notificationId) {
    private static final ObjectMapper DATA_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> DATA_TYPE = new TypeReference<>() {};

    public FcmDelivery(Long id, long fence, String token, FcmSendDto message, Instant expiresAt) {
        this(id, fence, token, message, expiresAt, id);
    }

    public static FcmDelivery from(FcmOutbox row) {
        return new FcmDelivery(row.getId(), row.getFence(), row.getMemberFcmToken().getToken(),
                FcmSendDto.from(row, data(row)),
                row.getExpiresAt().atZone(ZoneId.systemDefault()).toInstant(), notificationId(row));
    }

    private static Long notificationId(FcmOutbox row) {
        if (row.getNotificationId() != null) {
            return row.getNotificationId();
        }
        if (row.getNotificationType() == null) {
            return row.getId();
        }
        DeliveryMode mode = row.getNotificationType().deliveryMode();
        if (mode == DeliveryMode.PUSH_AND_CENTER || mode == DeliveryMode.CENTER_ONLY) {
            return row.getId();
        }
        return null;
    }

    static String encodeData(Map<String, String> data) {
        try {
            return DATA_MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("FCM data를 JSON으로 저장할 수 없습니다.", exception);
        }
    }

    private static Map<String, String> data(FcmOutbox row) {
        if (row.getDataPayload() != null) {
            try {
                Map<String, String> restored = DATA_MAPPER.readValue(row.getDataPayload(), DATA_TYPE);
                if (restored == null || restored.containsValue(null)) {
                    throw new IllegalArgumentException("FCM data_payload는 문자열 객체여야 합니다.");
                }
                return Map.copyOf(restored);
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("FCM data_payload를 읽을 수 없습니다.", exception);
            }
        }
        HashMap<String, String> restored = new HashMap<>();
        if (row.getDataType() != null) {
            restored.put("type", row.getDataType());
        }
        if (row.getDataRevision() != null) {
            restored.put("revision", row.getDataRevision().toString());
        }
        return Map.copyOf(restored);
    }
}
