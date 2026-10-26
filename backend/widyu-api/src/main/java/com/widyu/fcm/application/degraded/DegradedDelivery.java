package com.widyu.fcm.application.degraded;

import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.NotificationPolicy;
import com.widyu.fcm.application.FcmDelivery;
import com.widyu.fcm.dto.FcmSendDto;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.redis.connection.stream.MapRecord;

/** 저하 경로 스트림 항목 1건(LLD-0086 5.3 필드). 토큰이 들어 있어 toString을 로그에 쓰지 않는다. */
record DegradedDelivery(String streamId, Long seniorId, Long guardianId, Long tokenId, String token,
        String title, String body, String image, String category, long expiresAtMs) {

    static DegradedDelivery from(MapRecord<String, Object, Object> record) {
        Map<Object, Object> fields = record.getValue();
        String image = field(fields, "image");
        if (image.isEmpty()) {
            image = null;
        }
        return new DegradedDelivery(record.getId().getValue(),
                Long.valueOf(field(fields, "seniorId")), Long.valueOf(field(fields, "guardianId")),
                Long.valueOf(field(fields, "tokenId")), field(fields, "token"),
                field(fields, "title"), field(fields, "body"), image, field(fields, "category"),
                Long.parseLong(field(fields, "expiresAt")));
    }

    boolean expired(long nowMs) {
        return expiresAtMs <= nowMs;
    }

    /**
     * outbox 행이 없으므로 id·fence는 비우고 알림센터 ID도 싣지 않는다. 만료 시각을 넘겨 FCM TTL을 정상 경로와 같게 둔다.
     * eventId는 스트림 ID다.
     */
    FcmDelivery toFcmDelivery() {
        FcmSendDto message = FcmSendDto.builder()
                .title(title).content(body).image(image)
                .fcmCategory(FcmCategory.valueOf(category))
                .notificationPolicy(NotificationPolicy.safety("S01/S03/S04/S06", true))
                .eventId(streamId).seniorId(seniorId).relatedMemberId(seniorId)
                .emergency(true).build();
        message = message.withData(message.dataForEnqueue(streamId));
        return new FcmDelivery(null, 0L, token, message, Instant.ofEpochMilli(expiresAtMs), null);
    }

    /** DB 복구 후 fcm_notification 이력으로 옮길 성공 기록(LLD-0087이 소비). */
    Map<String, Object> reconcileRecord(long sentAtMs) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("guardianId", guardianId);
        values.put("tokenId", tokenId);
        values.put("seniorId", seniorId);
        values.put("title", title);
        values.put("body", body);
        values.put("image", image);
        values.put("category", category);
        values.put("sentAtMs", sentAtMs);
        values.put("streamId", streamId);
        return values;
    }

    @Override
    public String toString() {
        return "DegradedDelivery[streamId=" + streamId + ", seniorId=" + seniorId + "]";
    }

    private static String field(Map<Object, Object> fields, String name) {
        Object value = fields.get(name);
        if (value == null) {
            throw new IllegalArgumentException("저하 경로 스트림 항목에 필드가 없습니다: " + name);
        }
        return value.toString();
    }
}
