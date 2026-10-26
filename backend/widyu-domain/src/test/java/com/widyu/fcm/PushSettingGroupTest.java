package com.widyu.fcm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.widyu.member.MemberType;
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
        assertThat(PushSettingGroup.fromLegacy(FcmCategory.ALBUM, MemberType.SENIOR))
                .isEqualTo(PushSettingGroup.ALBUM);
        assertThat(PushSettingGroup.fromLegacy(FcmCategory.MEDICINE_SCHEDULE, MemberType.SENIOR))
                .isEqualTo(PushSettingGroup.GOAL);
    }

    @Test
    @DisplayName("회원 역할로 설정 그룹을 고르면 시니어와 보호자의 네 항목이 분리된다")
    void 역할별_설정_그룹을_고르면_네_항목이_분리된다() {
        // when / then
        assertThat(PushSettingGroup.stream().filter(group -> group.visibleFor(MemberType.SENIOR)).toList())
                .containsExactly(PushSettingGroup.ALBUM, PushSettingGroup.GOAL,
                        PushSettingGroup.HOME, PushSettingGroup.ETC);
        assertThat(PushSettingGroup.stream().filter(group -> group.visibleFor(MemberType.GUARDIAN)).toList())
                .containsExactly(PushSettingGroup.SAFETY, PushSettingGroup.SAFE_ZONE,
                        PushSettingGroup.MEDICATION_CHECK, PushSettingGroup.GENERAL);
        assertThat(PushSettingGroup.forSenior(FcmCategory.HEART_MESSAGE))
                .isEqualTo(PushSettingGroup.HOME);
        assertThat(PushSettingGroup.forSenior(FcmCategory.ETC))
                .isEqualTo(PushSettingGroup.ETC);
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
        assertThat(NotificationPolicy.safety("S01/S03/S04/S06", true).settingGroup()).isEqualTo(PushSettingGroup.SAFETY);
        assertThat(NotificationPolicy.safeZone("Z01").settingGroup()).isEqualTo(PushSettingGroup.SAFE_ZONE);
        assertThat(NotificationPolicy.medication("M04").settingGroup())
                .isEqualTo(PushSettingGroup.MEDICATION_CHECK);
        assertThat(NotificationPolicy.medication("M05").settingGroup())
                .isEqualTo(PushSettingGroup.GENERAL);
        assertThat(NotificationPolicy.safety("S08", false).settingGroup())
                .isEqualTo(PushSettingGroup.GENERAL);
        assertThat(NotificationPolicy.safeZone("Z02").settingGroup())
                .isEqualTo(PushSettingGroup.SAFE_ZONE);
        assertThat(NotificationPolicy.goal("G01-S/C").settingGroup()).isEqualTo(PushSettingGroup.GENERAL);
    }
}
