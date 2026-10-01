package com.widyu.fcm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PushSettingGroupTest {
    @Test
    @DisplayName("기존 카테고리를 변환하면 타입 없는 알림의 그룹을 반환한다")
    void 기존_카테고리를_변환하면_설정_그룹을_반환한다() {
        // when / then
        assertThat(PushSettingGroup.fromLegacy(FcmCategory.MEDICINE_SCHEDULE))
                .isEqualTo(PushSettingGroup.MEDICATION_CHECK);
        assertThat(PushSettingGroup.fromLegacy(FcmCategory.SAFE_ZONE))
                .isEqualTo(PushSettingGroup.SAFE_ZONE);
        assertThat(PushSettingGroup.fromLegacy(FcmCategory.ALBUM))
                .isEqualTo(PushSettingGroup.GENERAL);
        assertThat(PushSettingGroup.fromLegacy(FcmCategory.LOCATION_NOTICE)).isNull();
    }

    @Test
    @DisplayName("그룹의 방장 필수 속성을 조회하면 안전 두 그룹만 필수로 반환한다")
    void 그룹의_방장_필수_속성을_조회하면_안전_두_그룹만_필수로_반환한다() {
        // when / then
        assertThat(PushSettingGroup.SAFETY.isMandatoryForLeader()).isTrue();
        assertThat(PushSettingGroup.SAFE_ZONE.isMandatoryForLeader()).isTrue();
        assertThat(PushSettingGroup.MEDICATION_CHECK.isMandatoryForLeader()).isFalse();
        assertThat(PushSettingGroup.GENERAL.isMandatoryForLeader()).isFalse();
        assertThat(PushSettingGroup.NONE.isMandatoryForLeader()).isFalse();
        assertThat(PushSettingGroup.stream().toList()).doesNotContain(PushSettingGroup.NONE);
    }

    @Test
    @DisplayName("NONE 그룹을 설정 행으로 만들면 예외가 발생한다")
    void NONE_그룹을_설정_행으로_만들면_예외가_발생한다() {
        // when / then
        assertThatThrownBy(() -> MemberNotificationSetting.create(null, PushSettingGroup.NONE, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이벤트 타입의 설정 그룹을 읽으면 카테고리와 무관하게 승인된 정책을 반환한다")
    void 이벤트_타입의_설정_그룹을_읽으면_승인된_정책을_반환한다() {
        // when / then
        assertThat(NotificationType.HEART_RATE_EMERGENCY.settingGroup()).isEqualTo(PushSettingGroup.SAFETY);
        assertThat(NotificationType.SAFE_ZONE_EXITED.settingGroup()).isEqualTo(PushSettingGroup.SAFE_ZONE);
        assertThat(NotificationType.MEDICATION_PROOF_MISSING.settingGroup())
                .isEqualTo(PushSettingGroup.MEDICATION_CHECK);
        assertThat(NotificationType.MEDICATION_SCHEDULE_CHANGED.settingGroup())
                .isEqualTo(PushSettingGroup.GENERAL);
        assertThat(NotificationType.SAFETY_SENIOR_OK_NOTICE_HEART.settingGroup())
                .isEqualTo(PushSettingGroup.GENERAL);
        assertThat(NotificationType.SAFETY_SENIOR_OK_NOTICE_SAFE_ZONE.settingGroup())
                .isEqualTo(PushSettingGroup.GENERAL);
        assertThat(NotificationType.GOAL_ACHIEVED.settingGroup()).isEqualTo(PushSettingGroup.GENERAL);
    }
}
