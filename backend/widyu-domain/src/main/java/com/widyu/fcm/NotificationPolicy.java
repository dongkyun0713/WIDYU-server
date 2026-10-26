package com.widyu.fcm;

import java.util.Objects;

/** 기존 카테고리와 개별 발송에 필요한 값. 새로운 알림 종류를 등록하는 enum이 아니다. */
public record NotificationPolicy(
        FcmCategory fcmCategory,
        String centerFilter,
        DeliveryMode deliveryMode,
        NotificationPriority priority,
        RetentionClass retentionClass,
        PushSettingGroup settingGroup,
        String foregroundPresentation,
        String deepLinkTemplate,
        String copyCode,
        String legacyDataType
) {
    public NotificationPolicy {
        Objects.requireNonNull(fcmCategory, "알림 카테고리가 없습니다.");
        Objects.requireNonNull(deliveryMode, "알림 전달 방식이 없습니다.");
        Objects.requireNonNull(priority, "알림 우선순위가 없습니다.");
        Objects.requireNonNull(retentionClass, "알림 보존 정책이 없습니다.");
        Objects.requireNonNull(settingGroup, "알림 설정 그룹이 없습니다.");
        Objects.requireNonNull(foregroundPresentation, "알림 표시 방식이 없습니다.");
    }

    public static NotificationPolicy album(String code) {
        DeliveryMode mode = DeliveryMode.PUSH_AND_CENTER;
        NotificationPriority priority = NotificationPriority.INTERACTION;
        PushSettingGroup group = PushSettingGroup.GENERAL;
        String presentation = "BANNER";
        String path = "/album/post?postId={entityId}";
        if (code.startsWith("A01")) {
            mode = DeliveryMode.PUSH_ONLY;
            priority = NotificationPriority.PASSIVE;
            presentation = "NONE";
        } else if (code.startsWith("A05")) {
            mode = DeliveryMode.CENTER_ONLY;
            priority = NotificationPriority.PASSIVE;
            group = PushSettingGroup.NONE;
            presentation = "NONE";
        }
        if (code.startsWith("A03") || code.startsWith("A04")) {
            path += "&commentId={commentId}";
        } else if (code.startsWith("A06")) {
            path = "/post?postId={entityId}";
        } else if (code.startsWith("A07")) {
            path = "/album";
        }
        return new NotificationPolicy(FcmCategory.ALBUM, "ALBUM", mode, priority,
                RetentionClass.ROUTINE_90D, group, presentation, path, code, null);
    }

    public static NotificationPolicy medication(String code) {
        DeliveryMode mode = DeliveryMode.PUSH_AND_CENTER;
        NotificationPriority priority = NotificationPriority.INTERACTION;
        PushSettingGroup group = PushSettingGroup.GENERAL;
        String path = "/goal";
        if (code.equals("M02") || code.equals("M03")) {
            mode = DeliveryMode.PUSH_ONLY;
        } else if (code.equals("M04")) {
            priority = NotificationPriority.TIME_SENSITIVE;
            group = PushSettingGroup.MEDICATION_CHECK;
            path = "/goal/medicine?seniorId={seniorId}";
        }
        return new NotificationPolicy(FcmCategory.MEDICINE_SCHEDULE, "GOAL", mode, priority,
                RetentionClass.ROUTINE_90D, group, "BANNER", path, code, "MEDICATION_SCHEDULE_CHANGED");
    }

    public static NotificationPolicy medicationSync() {
        return new NotificationPolicy(FcmCategory.MEDICINE_SCHEDULE, null, DeliveryMode.DATA_ONLY,
                NotificationPriority.PASSIVE, RetentionClass.ROUTINE_90D, PushSettingGroup.NONE,
                "NONE", null, null, "MEDICATION_SCHEDULE_CHANGED");
    }

    public static NotificationPolicy goal(String code) {
        return goal(FcmCategory.TARGET, code, DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION);
    }

    public static NotificationPolicy health(String code) {
        return goal(FcmCategory.HEALTH_SCHEDULE, code,
                DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION);
    }

    public static NotificationPolicy walk(String code) {
        if (code.equals("W01")) {
            return goal(FcmCategory.WALK, code, DeliveryMode.PUSH_ONLY, NotificationPriority.PASSIVE);
        }
        return goal(FcmCategory.WALK, code, DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION);
    }

    private static NotificationPolicy goal(FcmCategory category, String code,
            DeliveryMode mode, NotificationPriority priority) {
        return new NotificationPolicy(category, "GOAL", mode, priority, RetentionClass.ROUTINE_90D,
                PushSettingGroup.GENERAL, "BANNER", "/goal", code, null);
    }

    public static NotificationPolicy point(String code) {
        return new NotificationPolicy(FcmCategory.TARGET, null, DeliveryMode.CENTER_ONLY,
                NotificationPriority.PASSIVE, RetentionClass.ROUTINE_90D, PushSettingGroup.NONE,
                "NONE", "/my/point", code, null);
    }

    public static NotificationPolicy message(String code) {
        return new NotificationPolicy(FcmCategory.HEART_MESSAGE, "MESSAGE", DeliveryMode.PUSH_AND_CENTER,
                NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL,
                "BANNER", "/notification", code, null);
    }

    public static NotificationPolicy selfCheck() {
        return new NotificationPolicy(FcmCategory.INCIDENT_SELF_CHECK, null, DeliveryMode.PUSH_ONLY,
                NotificationPriority.CRITICAL, RetentionClass.ROUTINE_90D, PushSettingGroup.NONE,
                "BANNER", null, "S01", "EMERGENCY_CONFIRM_REQUEST");
    }

    public static NotificationPolicy safety(String code, boolean critical) {
        NotificationPriority priority = NotificationPriority.INTERACTION;
        PushSettingGroup group = PushSettingGroup.GENERAL;
        String presentation = "BANNER";
        String trigger = null;
        if (critical) {
            priority = NotificationPriority.CRITICAL;
            group = PushSettingGroup.SAFETY;
            presentation = "MODAL";
        }
        if (code.contains("S04")) {
            trigger = "HEART_RATE_EMERGENCY";
        }
        return new NotificationPolicy(FcmCategory.HEART_MESSAGE, "LOCATION", DeliveryMode.PUSH_AND_CENTER,
                priority, RetentionClass.HEART_EMERGENCY_180D, group,
                presentation, "/location?seniorId={seniorId}", code, trigger);
    }

    public static NotificationPolicy safeZone(String code) {
        return new NotificationPolicy(FcmCategory.SAFE_ZONE, "LOCATION", DeliveryMode.PUSH_AND_CENTER,
                NotificationPriority.INTERACTION, RetentionClass.SAFE_ZONE_90D, PushSettingGroup.SAFE_ZONE,
                "BANNER", "/location?seniorId={seniorId}", code, null);
    }

    public static NotificationPolicy familyLeader() {
        return new NotificationPolicy(FcmCategory.ETC, null, DeliveryMode.PUSH_AND_CENTER,
                NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL,
                "BANNER", "/family-manage", "R01", null);
    }

    public static NotificationPolicy fromLegacyName(String value) {
        return LegacyNotificationPolicies.find(value);
    }

    public String name() {
        return fcmCategory.name();
    }

    public String dataTypeValue() {
        return legacyDataType;
    }
}
