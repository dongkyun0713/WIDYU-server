package com.widyu.fcm;

import java.util.Map;

/** 제거한 상세 enum의 기존 DB 값 전용 읽기 변환. 새 알림 분류에는 사용하지 않는다. */
final class LegacyNotificationPolicies {
    private static final Map<String, NotificationPolicy> PREVIOUS = Map.ofEntries(
            Map.entry("ALBUM_UPLOAD_COMPLETE", NotificationPolicy.album("A01")),
            Map.entry("ALBUM_CREATED", NotificationPolicy.album("A02-S/C")),
            Map.entry("ALBUM_COMMENTED", NotificationPolicy.album("A03")),
            Map.entry("ALBUM_REPLIED", NotificationPolicy.album("A04")),
            Map.entry("ALBUM_LIKED", NotificationPolicy.album("A05")),
            Map.entry("ALBUM_UNLOCKED", NotificationPolicy.album("A06-L/Z")),
            Map.entry("ALBUM_ALL_VIEWED", NotificationPolicy.album("A07")),
            Map.entry("MEDICATION_REMINDER_10", NotificationPolicy.medication("M02")),
            Map.entry("MEDICATION_REMINDER_20", NotificationPolicy.medication("M03")),
            Map.entry("MEDICATION_PROOF_MISSING", NotificationPolicy.medication("M04")),
            Map.entry("MEDICATION_SCHEDULE_CHANGED", NotificationPolicy.medication("M05")),
            Map.entry("MEDICATION_SCHEDULE_CREATED", NotificationPolicy.medication("M08")),
            Map.entry("MEDICATION_SCHEDULE_DELETED", NotificationPolicy.medication("M09")),
            Map.entry("MEDICATION_SCHEDULE_SYNC", NotificationPolicy.medicationSync()),
            Map.entry("HEALTH_SCHEDULE_UPCOMING", NotificationPolicy.health("H01-S/C-SELF/C-SENIOR-OS/INAPP")),
            Map.entry("HEALTH_SCHEDULE_CREATED", NotificationPolicy.health("H02")),
            Map.entry("HEALTH_SCHEDULE_UPDATED", NotificationPolicy.health("H03")),
            Map.entry("HEALTH_SCHEDULE_DELETED", NotificationPolicy.health("H04")),
            Map.entry("WALK_GOAL_UNMET", NotificationPolicy.walk("W01")),
            Map.entry("WALK_GOAL_CHANGED", NotificationPolicy.walk("W02")),
            Map.entry("GOAL_ACHIEVED", NotificationPolicy.goal("G01-S/C")),
            Map.entry("GOALS_ACHIEVED_GROUPED", NotificationPolicy.goal("G02-S/C")),
            Map.entry("POINT_EARNED", NotificationPolicy.point("P01")),
            Map.entry("POINT_USED", NotificationPolicy.point("P02")),
            Map.entry("HEART_MESSAGE_RECEIVED", NotificationPolicy.message("X01-OS/INAPP")),
            Map.entry("CHEER_MESSAGE_RECEIVED", NotificationPolicy.message("X02-OS/INAPP")),
            Map.entry("SAFETY_SELF_CHECK", NotificationPolicy.selfCheck()),
            Map.entry("HEART_RATE_EMERGENCY", NotificationPolicy.safety("S01/S03/S04/S06", true)),
            Map.entry("SELF_HELP_REQUESTED", NotificationPolicy.safety("K01", true)),
            Map.entry("SELF_HELP_REQUEST_CANCELLED", NotificationPolicy.safety("K02", false)),
            Map.entry("SAFETY_SENIOR_OK_NOTICE_HEART", NotificationPolicy.safety("S08", false)),
            Map.entry("SAFE_ZONE_EXITED", NotificationPolicy.safeZone("Z01")),
            Map.entry("SAFE_ZONE_ENTERED", NotificationPolicy.safeZone("Z02")),
            Map.entry("FAMILY_LEADER_CHANGED", NotificationPolicy.familyLeader()));

    private LegacyNotificationPolicies() {}

    static NotificationPolicy find(String name) {
        NotificationPolicy policy = PREVIOUS.get(name);
        if (policy == null) {
            throw new IllegalArgumentException("지원하지 않는 이전 알림 정책입니다.");
        }
        return policy;
    }
}
