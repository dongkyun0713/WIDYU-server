package com.widyu.fcm;

/** 제품 이벤트의 전달·표시 정책. 설정 그룹은 W5 전까지 판정에 사용하지 않는다. */
public enum NotificationType {
    ALBUM_UPLOAD_COMPLETE(FcmCategory.ALBUM, "ALBUM", DeliveryMode.PUSH_ONLY, NotificationPriority.PASSIVE, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "NONE", "widyu://albums/{entityId}", "A01"),
    ALBUM_CREATED(FcmCategory.ALBUM, "ALBUM", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "BANNER", "widyu://albums/{entityId}", "A02-S/C"),
    ALBUM_COMMENTED(FcmCategory.ALBUM, "ALBUM", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "BANNER", "widyu://albums/{entityId}/comments/{commentId}", "A03"),
    ALBUM_REPLIED(FcmCategory.ALBUM, "ALBUM", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "BANNER", "widyu://albums/{entityId}/comments/{commentId}", "A04"),
    ALBUM_LIKED(FcmCategory.ALBUM, "ALBUM", DeliveryMode.CENTER_ONLY, NotificationPriority.PASSIVE, RetentionClass.ROUTINE_90D, PushSettingGroup.NONE, "NONE", "widyu://albums/{entityId}", "A05"),
    ALBUM_UNLOCKED(FcmCategory.ALBUM, "ALBUM", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "BANNER", "/post?postId={entityId}", "A06-L/Z"),
    ALBUM_ALL_VIEWED(FcmCategory.ALBUM, "ALBUM", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "BANNER", "/album", "A07"),
    MEDICATION_REMINDER_10(FcmCategory.MEDICINE_SCHEDULE, "GOAL", DeliveryMode.PUSH_ONLY, NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "BANNER", "widyu://medication/proof/{entityId}", "M02"),
    MEDICATION_REMINDER_20(FcmCategory.MEDICINE_SCHEDULE, "GOAL", DeliveryMode.PUSH_ONLY, NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "BANNER", "widyu://medication/proof/{entityId}", "M03"),
    MEDICATION_PROOF_MISSING(FcmCategory.MEDICINE_SCHEDULE, "GOAL", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.TIME_SENSITIVE, RetentionClass.ROUTINE_90D, PushSettingGroup.MEDICATION_CHECK, "BANNER", "/goal/medicine?seniorId={seniorId}", "M04"),
    MEDICATION_SCHEDULE_CHANGED(FcmCategory.MEDICINE_SCHEDULE, "GOAL", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "BANNER", "widyu://medication/schedules", "M05", "MEDICATION_SCHEDULE_CHANGED"),
    HEALTH_SCHEDULE_UPCOMING(FcmCategory.HEALTH_SCHEDULE, "GOAL", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "BANNER", "widyu://health/schedules/{entityId}", "H01-S/C-SELF/C-SENIOR-OS/INAPP"),
    WALK_GOAL_UNMET(FcmCategory.WALK, "GOAL", DeliveryMode.PUSH_ONLY, NotificationPriority.PASSIVE, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "BANNER", "widyu://walk/goal", "W01"),
    GOAL_ACHIEVED(FcmCategory.TARGET, "GOAL", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "BANNER", "widyu://goals/{entityId}", "G01-S/C"),
    GOALS_ACHIEVED_GROUPED(FcmCategory.TARGET, "GOAL", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "BANNER", "widyu://goals", "G02-S/C"),
    POINT_EARNED(FcmCategory.TARGET, null, DeliveryMode.CENTER_ONLY, NotificationPriority.PASSIVE, RetentionClass.ROUTINE_90D, PushSettingGroup.NONE, "NONE", "widyu://points", "P01"),
    POINT_USED(FcmCategory.TARGET, null, DeliveryMode.CENTER_ONLY, NotificationPriority.PASSIVE, RetentionClass.ROUTINE_90D, PushSettingGroup.NONE, "NONE", "widyu://points", "P02"),
    HEART_MESSAGE_RECEIVED(FcmCategory.HEART_MESSAGE, "MESSAGE", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "BANNER", "widyu://messages/{entityId}", "X01-OS/INAPP"),
    CHEER_MESSAGE_RECEIVED(FcmCategory.HEART_MESSAGE, "MESSAGE", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "BANNER", "widyu://messages/{entityId}", "X02-OS/INAPP"),
    SAFETY_SELF_CHECK(FcmCategory.INCIDENT_SELF_CHECK, null, DeliveryMode.PUSH_ONLY, NotificationPriority.CRITICAL, RetentionClass.ROUTINE_90D, PushSettingGroup.NONE, "BANNER", "widyu://incident/{entityId}", "S01"),
    HEART_RATE_EMERGENCY(FcmCategory.HEART_MESSAGE, "LOCATION", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.CRITICAL, RetentionClass.HEART_EMERGENCY_180D, PushSettingGroup.SAFETY, "MODAL", "/location?seniorId={seniorId}", "S01/S03/S04/S06"),
    SAFE_ZONE_EXITED(FcmCategory.SAFE_ZONE, "LOCATION", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION, RetentionClass.SAFE_ZONE_90D, PushSettingGroup.SAFE_ZONE, "BANNER", "/location?seniorId={seniorId}", "Z01"),
    SAFE_ZONE_ENTERED(FcmCategory.SAFE_ZONE, "LOCATION", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION, RetentionClass.SAFE_ZONE_90D, PushSettingGroup.SAFE_ZONE, "BANNER", "/location?seniorId={seniorId}", "Z02"),
    SAFETY_SENIOR_OK_NOTICE_HEART(FcmCategory.HEART_MESSAGE, "LOCATION", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION, RetentionClass.HEART_EMERGENCY_180D, PushSettingGroup.GENERAL, "BANNER", "/location?seniorId={seniorId}", "S08"),
    FAMILY_LEADER_CHANGED(FcmCategory.ETC, null, DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "BANNER", "/family-manage", "R01"),
    MEDICATION_SCHEDULE_CREATED(FcmCategory.MEDICINE_SCHEDULE, "GOAL", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "BANNER", "widyu://medication/schedules", "M08", "MEDICATION_SCHEDULE_CHANGED"),
    MEDICATION_SCHEDULE_DELETED(FcmCategory.MEDICINE_SCHEDULE, "GOAL", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "BANNER", "widyu://medication/schedules", "M09", "MEDICATION_SCHEDULE_CHANGED"),
    MEDICATION_SCHEDULE_SYNC(FcmCategory.MEDICINE_SCHEDULE, null, DeliveryMode.DATA_ONLY, NotificationPriority.PASSIVE, RetentionClass.ROUTINE_90D, PushSettingGroup.NONE, "NONE", null, null, "MEDICATION_SCHEDULE_CHANGED"),
    HEALTH_SCHEDULE_CREATED(FcmCategory.HEALTH_SCHEDULE, "GOAL", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "BANNER", "widyu://health/schedules/{entityId}", "H02"),
    HEALTH_SCHEDULE_UPDATED(FcmCategory.HEALTH_SCHEDULE, "GOAL", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "BANNER", "widyu://health/schedules/{entityId}", "H03"),
    HEALTH_SCHEDULE_DELETED(FcmCategory.HEALTH_SCHEDULE, "GOAL", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "BANNER", "widyu://goals", "H04"),
    WALK_GOAL_CHANGED(FcmCategory.WALK, "GOAL", DeliveryMode.PUSH_AND_CENTER, NotificationPriority.INTERACTION, RetentionClass.ROUTINE_90D, PushSettingGroup.GENERAL, "BANNER", "widyu://walk/goal", "W02");

    private final FcmCategory fcmCategory;
    private final String centerFilter;
    private final DeliveryMode deliveryMode;
    private final NotificationPriority priority;
    private final RetentionClass retentionClass;
    private final PushSettingGroup settingGroup;
    private final String foregroundPresentation;
    private final String deepLinkTemplate;
    private final String copyCode;
    private final String legacyDataType;

    NotificationType(FcmCategory fcmCategory, String centerFilter, DeliveryMode deliveryMode,
            NotificationPriority priority, RetentionClass retentionClass, PushSettingGroup settingGroup,
            String foregroundPresentation, String deepLinkTemplate, String copyCode) {
        this(fcmCategory, centerFilter, deliveryMode, priority, retentionClass, settingGroup,
                foregroundPresentation, deepLinkTemplate, copyCode, null);
    }

    NotificationType(FcmCategory fcmCategory, String centerFilter, DeliveryMode deliveryMode,
            NotificationPriority priority, RetentionClass retentionClass, PushSettingGroup settingGroup,
            String foregroundPresentation, String deepLinkTemplate, String copyCode, String legacyDataType) {
        this.fcmCategory = fcmCategory;
        this.centerFilter = centerFilter;
        this.deliveryMode = deliveryMode;
        this.priority = priority;
        this.retentionClass = retentionClass;
        this.settingGroup = settingGroup;
        this.foregroundPresentation = foregroundPresentation;
        this.deepLinkTemplate = deepLinkTemplate;
        this.copyCode = copyCode;
        this.legacyDataType = legacyDataType;
    }

    public FcmCategory fcmCategory() { return fcmCategory; }
    public String centerFilter() { return centerFilter; }
    public DeliveryMode deliveryMode() { return deliveryMode; }
    public NotificationPriority priority() { return priority; }
    public RetentionClass retentionClass() { return retentionClass; }
    public PushSettingGroup settingGroup() { return settingGroup; }
    public String foregroundPresentation() { return foregroundPresentation; }
    public String deepLinkTemplate() { return deepLinkTemplate; }
    public String copyCode() { return copyCode; }
    public String legacyDataType() { return legacyDataType; }
    public String dataTypeValue() {
        if (legacyDataType != null) {
            return legacyDataType;
        }
        return name();
    }
}
