package com.widyu.fcm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NotificationPolicyTest {

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', nullValues = "null", value = {
            "ALBUM_UPLOAD_COMPLETE|ALBUM|ALBUM|PUSH_ONLY|PASSIVE|ROUTINE_90D|GENERAL|NONE|/album/post?postId={entityId}|A01",
            "ALBUM_CREATED|ALBUM|ALBUM|PUSH_AND_CENTER|INTERACTION|ROUTINE_90D|GENERAL|BANNER|/album/post?postId={entityId}|A02-S/C",
            "ALBUM_COMMENTED|ALBUM|ALBUM|PUSH_AND_CENTER|INTERACTION|ROUTINE_90D|GENERAL|BANNER|/album/post?postId={entityId}&commentId={commentId}|A03",
            "ALBUM_REPLIED|ALBUM|ALBUM|PUSH_AND_CENTER|INTERACTION|ROUTINE_90D|GENERAL|BANNER|/album/post?postId={entityId}&commentId={commentId}|A04",
            "ALBUM_LIKED|ALBUM|ALBUM|CENTER_ONLY|PASSIVE|ROUTINE_90D|NONE|NONE|/album/post?postId={entityId}|A05",
            "ALBUM_UNLOCKED|ALBUM|ALBUM|PUSH_AND_CENTER|INTERACTION|ROUTINE_90D|GENERAL|BANNER|/post?postId={entityId}|A06-L/Z",
            "ALBUM_ALL_VIEWED|ALBUM|ALBUM|PUSH_AND_CENTER|INTERACTION|ROUTINE_90D|GENERAL|BANNER|/album|A07",
            "MEDICATION_REMINDER_10|MEDICINE_SCHEDULE|GOAL|PUSH_ONLY|INTERACTION|ROUTINE_90D|GENERAL|BANNER|/goal|M02",
            "MEDICATION_REMINDER_20|MEDICINE_SCHEDULE|GOAL|PUSH_ONLY|INTERACTION|ROUTINE_90D|GENERAL|BANNER|/goal|M03",
            "MEDICATION_PROOF_MISSING|MEDICINE_SCHEDULE|GOAL|PUSH_AND_CENTER|TIME_SENSITIVE|ROUTINE_90D|MEDICATION_CHECK|BANNER|/goal/medicine?seniorId={seniorId}|M04",
            "MEDICATION_SCHEDULE_CHANGED|MEDICINE_SCHEDULE|GOAL|PUSH_AND_CENTER|INTERACTION|ROUTINE_90D|GENERAL|BANNER|/goal|M05",
            "HEALTH_SCHEDULE_UPCOMING|HEALTH_SCHEDULE|GOAL|PUSH_AND_CENTER|INTERACTION|ROUTINE_90D|GENERAL|BANNER|/goal|H01-S/C-SELF/C-SENIOR-OS/INAPP",
            "WALK_GOAL_UNMET|WALK|GOAL|PUSH_ONLY|PASSIVE|ROUTINE_90D|GENERAL|BANNER|/goal|W01",
            "GOAL_ACHIEVED|TARGET|GOAL|PUSH_AND_CENTER|INTERACTION|ROUTINE_90D|GENERAL|BANNER|/goal|G01-S/C",
            "GOALS_ACHIEVED_GROUPED|TARGET|GOAL|PUSH_AND_CENTER|INTERACTION|ROUTINE_90D|GENERAL|BANNER|/goal|G02-S/C",
            "POINT_EARNED|TARGET|null|CENTER_ONLY|PASSIVE|ROUTINE_90D|NONE|NONE|/my/point|P01",
            "POINT_USED|TARGET|null|CENTER_ONLY|PASSIVE|ROUTINE_90D|NONE|NONE|/my/point|P02",
            "HEART_MESSAGE_RECEIVED|HEART_MESSAGE|MESSAGE|PUSH_AND_CENTER|INTERACTION|ROUTINE_90D|GENERAL|BANNER|/notification|X01-OS/INAPP",
            "CHEER_MESSAGE_RECEIVED|HEART_MESSAGE|MESSAGE|PUSH_AND_CENTER|INTERACTION|ROUTINE_90D|GENERAL|BANNER|/notification|X02-OS/INAPP",
            "SAFETY_SELF_CHECK|INCIDENT_SELF_CHECK|null|PUSH_ONLY|CRITICAL|ROUTINE_90D|NONE|BANNER|null|S01",
            "HEART_RATE_EMERGENCY|HEART_MESSAGE|LOCATION|PUSH_AND_CENTER|CRITICAL|HEART_EMERGENCY_180D|SAFETY|MODAL|/location?seniorId={seniorId}|S01/S03/S04/S06",
            "SELF_HELP_REQUESTED|HEART_MESSAGE|LOCATION|PUSH_AND_CENTER|CRITICAL|HEART_EMERGENCY_180D|SAFETY|MODAL|/location?seniorId={seniorId}|K01",
            "SELF_HELP_REQUEST_CANCELLED|HEART_MESSAGE|LOCATION|PUSH_AND_CENTER|INTERACTION|HEART_EMERGENCY_180D|GENERAL|BANNER|/location?seniorId={seniorId}|K02",
            "SAFE_ZONE_EXITED|SAFE_ZONE|LOCATION|PUSH_AND_CENTER|INTERACTION|SAFE_ZONE_90D|SAFE_ZONE|BANNER|/location?seniorId={seniorId}|Z01",
            "SAFE_ZONE_ENTERED|SAFE_ZONE|LOCATION|PUSH_AND_CENTER|INTERACTION|SAFE_ZONE_90D|SAFE_ZONE|BANNER|/location?seniorId={seniorId}|Z02",
            "SAFETY_SENIOR_OK_NOTICE_HEART|HEART_MESSAGE|LOCATION|PUSH_AND_CENTER|INTERACTION|HEART_EMERGENCY_180D|GENERAL|BANNER|/location?seniorId={seniorId}|S08",
            "FAMILY_LEADER_CHANGED|ETC|null|PUSH_AND_CENTER|INTERACTION|ROUTINE_90D|GENERAL|BANNER|/family-manage|R01",
            "MEDICATION_SCHEDULE_CREATED|MEDICINE_SCHEDULE|GOAL|PUSH_AND_CENTER|INTERACTION|ROUTINE_90D|GENERAL|BANNER|/goal|M08",
            "MEDICATION_SCHEDULE_DELETED|MEDICINE_SCHEDULE|GOAL|PUSH_AND_CENTER|INTERACTION|ROUTINE_90D|GENERAL|BANNER|/goal|M09",
            "MEDICATION_SCHEDULE_SYNC|MEDICINE_SCHEDULE|null|DATA_ONLY|PASSIVE|ROUTINE_90D|NONE|NONE|null|null",
            "HEALTH_SCHEDULE_CREATED|HEALTH_SCHEDULE|GOAL|PUSH_AND_CENTER|INTERACTION|ROUTINE_90D|GENERAL|BANNER|/goal|H02",
            "HEALTH_SCHEDULE_UPDATED|HEALTH_SCHEDULE|GOAL|PUSH_AND_CENTER|INTERACTION|ROUTINE_90D|GENERAL|BANNER|/goal|H03",
            "HEALTH_SCHEDULE_DELETED|HEALTH_SCHEDULE|GOAL|PUSH_AND_CENTER|INTERACTION|ROUTINE_90D|GENERAL|BANNER|/goal|H04",
            "WALK_GOAL_CHANGED|WALK|GOAL|PUSH_AND_CENTER|INTERACTION|ROUTINE_90D|GENERAL|BANNER|/goal|W02"
    })
    @DisplayName("이전 상세 저장값을 변환하면 전달 우선순위 보존 설정 경로 문구를 유지한다")
    void 이전_정책_속성을_보존한다(String previous, String category, String filter, String mode,
            String priority, String retention, String group, String presentation, String path, String copy) {
        // given / when
        NotificationPolicy policy = converter.convertToEntityAttribute(previous);
        // then
        assertThat(policy.fcmCategory().name()).isEqualTo(category);
        assertThat(policy.centerFilter()).isEqualTo(filter);
        assertThat(policy.deliveryMode().name()).isEqualTo(mode);
        assertThat(policy.priority().name()).isEqualTo(priority);
        assertThat(policy.retentionClass().name()).isEqualTo(retention);
        assertThat(policy.settingGroup().name()).isEqualTo(group);
        assertThat(policy.foregroundPresentation()).isEqualTo(presentation);
        assertThat(policy.deepLinkTemplate()).isEqualTo(path);
        assertThat(policy.copyCode()).isEqualTo(copy);
    }
    private final NotificationPolicyConverter converter = new NotificationPolicyConverter();

    @ParameterizedTest
    @ValueSource(strings = {
            "ALBUM_UPLOAD_COMPLETE",
            "ALBUM_CREATED",
            "ALBUM_COMMENTED",
            "ALBUM_REPLIED",
            "ALBUM_LIKED",
            "ALBUM_UNLOCKED",
            "ALBUM_ALL_VIEWED",
            "MEDICATION_REMINDER_10",
            "MEDICATION_REMINDER_20",
            "MEDICATION_PROOF_MISSING",
            "MEDICATION_SCHEDULE_CHANGED",
            "MEDICATION_SCHEDULE_CREATED",
            "MEDICATION_SCHEDULE_DELETED",
            "MEDICATION_SCHEDULE_SYNC",
            "HEALTH_SCHEDULE_UPCOMING",
            "HEALTH_SCHEDULE_CREATED",
            "HEALTH_SCHEDULE_UPDATED",
            "HEALTH_SCHEDULE_DELETED",
            "WALK_GOAL_UNMET",
            "WALK_GOAL_CHANGED",
            "GOAL_ACHIEVED",
            "GOALS_ACHIEVED_GROUPED",
            "POINT_EARNED",
            "POINT_USED",
            "HEART_MESSAGE_RECEIVED",
            "CHEER_MESSAGE_RECEIVED",
            "SAFETY_SELF_CHECK",
            "HEART_RATE_EMERGENCY",
            "SELF_HELP_REQUESTED",
            "SELF_HELP_REQUEST_CANCELLED",
            "SAFETY_SENIOR_OK_NOTICE_HEART",
            "SAFE_ZONE_EXITED",
            "SAFE_ZONE_ENTERED",
            "FAMILY_LEADER_CHANGED"})
    @DisplayName("이전 저장값을 읽고 다시 저장하면 enum 이름 대신 정책 값 자체로 왕복한다")
    void 이전_저장값은_정책_값으로_왕복한다(String previous) {
        // given
        NotificationPolicy policy = converter.convertToEntityAttribute(previous);
        // when
        String stored = converter.convertToDatabaseColumn(policy);
        NotificationPolicy restored = converter.convertToEntityAttribute(stored);
        // then
        assertThat(stored).startsWith("{").contains("\"fcmCategory\"");
        assertThat(restored).isEqualTo(policy);
        assertThat(policy.fcmCategory()).isNotNull();
    }

    @Test
    @DisplayName("분류를 확인하면 기존 카테고리만 사용하고 정책 값은 enum이 아니다")
    void 분류는_기존_카테고리를_사용한다() {
        // given
        NotificationPolicy album = NotificationPolicy.album("A02-S/C");
        NotificationPolicy heart = NotificationPolicy.safety("S01/S03/S04/S06", true);
        // when / then
        assertThat(NotificationPolicy.class.isEnum()).isFalse();
        assertThat(album.fcmCategory()).isEqualTo(FcmCategory.ALBUM);
        assertThat(heart.fcmCategory()).isEqualTo(FcmCategory.HEART_MESSAGE);
        assertThat(heart.priority()).isEqualTo(NotificationPriority.CRITICAL);
        assertThat(heart.retentionClass()).isEqualTo(RetentionClass.HEART_EMERGENCY_180D);
        assertThat(heart.settingGroup()).isEqualTo(PushSettingGroup.SAFETY);
    }

    @Test
    @DisplayName("복약 동기화와 본인확인 정책을 만들면 기존 전송 예외를 유지한다")
    void 동기화와_본인확인은_기존_전송_예외를_유지한다() {
        // given
        NotificationPolicy sync = NotificationPolicy.medicationSync();
        NotificationPolicy check = NotificationPolicy.selfCheck();
        // when / then
        assertThat(sync.deliveryMode()).isEqualTo(DeliveryMode.DATA_ONLY);
        assertThat(sync.settingGroup()).isEqualTo(PushSettingGroup.NONE);
        assertThat(sync.legacyDataType()).isEqualTo("MEDICATION_SCHEDULE_CHANGED");
        assertThat(check.deliveryMode()).isEqualTo(DeliveryMode.PUSH_ONLY);
        assertThat(check.legacyDataType()).isEqualTo("EMERGENCY_CONFIRM_REQUEST");
        assertThat(check.deepLinkTemplate()).isNull();
    }

    @Test
    @DisplayName("알 수 없거나 깨진 정책 저장값이면 조용히 기본 정책으로 바꾸지 않고 예외가 발생한다")
    void 잘못된_정책_저장값은_예외가_발생한다() {
        // given / when / then
        assertThatThrownBy(() -> converter.convertToEntityAttribute("UNKNOWN_OLD_TYPE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.convertToEntityAttribute("{broken"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.convertToEntityAttribute("{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
