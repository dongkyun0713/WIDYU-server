package com.widyu.fcm.dto;

import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.FcmOutbox;
import com.widyu.fcm.NotificationType;
import lombok.Builder;
import java.util.HashMap;
import java.util.Map;

@Builder
public record FcmSendDto(
        String title,
        String content,
        FcmCategory fcmCategory,
        String scheme,
        String image,
        boolean emergency,
        Long relatedMemberId,
        Map<String, String> data,
        /** 이 알림을 낳은 판정 기록. 전송이 성공하면 그 행에 도달 사실을 채운다(LLD-0053 5.2). 대개 null이다. */
        String decisionId,
        NotificationType notificationType,
        String eventId,
        String deepLink,
        String entityId,
        Long seniorId,
        String actorDisplayName,
        String effectiveFromDate,
        String groupKey,
        String centerTitle,
        String centerBody,
        String seniorDisplayName,
        Integer remainingLockedCount
) {
    public FcmSendDto {
        if (data == null) {
            data = Map.of();
        }
    }

    public FcmSendDto(String title, String content, FcmCategory category, String scheme, String image) {
        this(title, content, category, scheme, image, false, null, Map.of(), null);
    }

    public FcmSendDto(String title, String content, FcmCategory category, String scheme, String image,
            boolean emergency, Long relatedMemberId, Map<String, String> data, String decisionId) {
        this(title, content, category, scheme, image, emergency, relatedMemberId, data, decisionId,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static FcmSendDto from(FcmOutbox row, Map<String, String> restoredData) {
        return FcmSendDto.builder().title(row.getTitle()).content(row.getBody())
                .fcmCategory(row.getFcmCategory()).scheme(row.getScheme()).image(row.getImage())
                .emergency(row.isEmergency()).relatedMemberId(row.getRelatedMemberId())
                .data(restoredData).decisionId(row.getDecisionId())
                .notificationType(row.getNotificationType()).build();
    }

    public static FcmSendDto of(NotificationType type, NotificationCopy copy, String entityId,
            String deepLink, Long relatedMemberId, Long seniorId, String image) {
        return FcmSendDto.builder().title(copy.title()).content(copy.body())
                .fcmCategory(type.fcmCategory()).notificationType(type)
                .entityId(entityId).deepLink(deepLink).relatedMemberId(relatedMemberId)
                .seniorId(seniorId).image(image).build();
    }

    /** type이 없는 기존 호출자의 data는 그대로 둔다. */
    public Map<String, String> dataForEnqueue(String resolvedEventId) {
        if (notificationType == null) {
            return data;
        }
        HashMap<String, String> values = new HashMap<>(data);
        values.put("eventId", resolvedEventId);
        values.put("type", notificationType.name());
        values.put("priority", notificationType.priority().wireValue());
        values.put("foregroundPresentation", notificationType.foregroundPresentation());
        values.put("deepLink", resolvedDeepLink());
        putIfPresent(values, "entityId", entityId);
        if (seniorId != null) {
            values.put("seniorId", seniorId.toString());
        }
        putIfPresent(values, "actorDisplayName", actorDisplayName);
        putIfPresent(values, "effectiveFromDate", effectiveFromDate);
        putIfPresent(values, "groupKey", groupKey);
        putIfPresent(values, "inAppTitle", centerTitle);
        putIfPresent(values, "inAppBody", centerBody);
        putIfPresent(values, "seniorDisplayName", seniorDisplayName);
        if (remainingLockedCount != null) {
            values.put("remainingLockedCount", remainingLockedCount.toString());
        }
        return Map.copyOf(values);
    }

    public String centerTitleOrTitle() {
        if (centerTitle != null) {
            return centerTitle;
        }
        return title;
    }

    public String centerBodyOrContent() {
        if (centerBody != null) {
            return centerBody;
        }
        return content;
    }

    private String resolvedDeepLink() {
        if (deepLink != null && !deepLink.isBlank()) {
            return deepLink;
        }
        if (scheme != null && !scheme.isBlank()) {
            return scheme;
        }
        String template = notificationType.deepLinkTemplate();
        if (template == null) {
            return "";
        }
        if (entityId != null) {
            template = template.replace("{entityId}", entityId);
        }
        if (seniorId != null) {
            template = template.replace("{seniorId}", seniorId.toString());
        }
        String commentId = data.get("commentId");
        if (commentId != null) {
            template = template.replace("{commentId}", commentId);
        }
        if (template.contains("{")) {
            return "";
        }
        return template;
    }

    private static void putIfPresent(Map<String, String> values, String key, String value) {
        if (value != null) {
            values.put(key, value);
        }
    }

    public FcmSendDto withRelatedMember(Long memberId) {
        return new FcmSendDto(title, content, fcmCategory, scheme, image, emergency, memberId, data, decisionId,
                notificationType, eventId, deepLink, entityId, seniorId, actorDisplayName, effectiveFromDate, groupKey,
                centerTitle, centerBody, seniorDisplayName, remainingLockedCount);
    }

    public FcmSendDto withData(Map<String, String> newData) {
        return new FcmSendDto(title, content, fcmCategory, scheme, image, emergency, relatedMemberId, newData, decisionId,
                notificationType, eventId, deepLink, entityId, seniorId, actorDisplayName, effectiveFromDate, groupKey,
                centerTitle, centerBody, seniorDisplayName, remainingLockedCount);
    }

    public FcmSendDto withCenterCopy(NotificationCopy copy) {
        return new FcmSendDto(title, content, fcmCategory, scheme, image, emergency, relatedMemberId, data, decisionId,
                notificationType, eventId, deepLink, entityId, seniorId, actorDisplayName, effectiveFromDate, groupKey,
                copy.title(), copy.body(), seniorDisplayName, remainingLockedCount);
    }

    public FcmSendDto withSeniorUnlockDetails(String displayName, Integer count) {
        return new FcmSendDto(title, content, fcmCategory, scheme, image, emergency, relatedMemberId, data, decisionId,
                notificationType, eventId, deepLink, entityId, seniorId, actorDisplayName, effectiveFromDate, groupKey,
                centerTitle, centerBody, displayName, count);
    }
}
