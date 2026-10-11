package com.widyu.fcm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationTypeTest {
    @Test
    @DisplayName("승인된 서버 알림을 등록하면 32개 타입과 네 전달 방식을 반환한다")
    void 승인된_서버_알림을_등록하면_타입과_전달_방식을_반환한다() {
        // given / when
        NotificationType[] types = NotificationType.values();

        // then
        assertThat(types).hasSize(32);
        assertThat(Arrays.stream(types).map(NotificationType::deliveryMode).distinct())
                .containsExactlyInAnyOrder(DeliveryMode.values());
        assertThat(Arrays.stream(types).map(NotificationType::name))
                .doesNotContain("MEDICATION_DUE", "SAFETY_SENIOR_OK_NOTICE");
        assertThat(types).allMatch(type -> type.fcmCategory() != null
                && type.deliveryMode() != null && type.priority() != null
                && type.retentionClass() != null && type.settingGroup() != null
                && type.foregroundPresentation() != null);
    }

    @Test
    @DisplayName("안전 상황별 타입을 조회하면 위치 필터와 서로 다른 보존등급을 반환한다")
    void 안전_상황별_타입을_조회하면_위치_필터와_보존등급을_반환한다() {
        // given / when
        NotificationType emergency = NotificationType.HEART_RATE_EMERGENCY;
        NotificationType heartOk = NotificationType.SAFETY_SENIOR_OK_NOTICE_HEART;
        NotificationType zoneOk = NotificationType.SAFETY_SENIOR_OK_NOTICE_SAFE_ZONE;

        // then
        assertThat(emergency.centerFilter()).isEqualTo("LOCATION");
        assertThat(emergency.priority()).isEqualTo(NotificationPriority.CRITICAL);
        assertThat(emergency.settingGroup()).isEqualTo(PushSettingGroup.SAFETY);
        assertThat(heartOk.retentionClass()).isEqualTo(RetentionClass.HEART_EMERGENCY_180D);
        assertThat(zoneOk.retentionClass()).isEqualTo(RetentionClass.SAFE_ZONE_90D);
        assertThat(heartOk.priority()).isEqualTo(NotificationPriority.INTERACTION);
        assertThat(zoneOk.settingGroup()).isEqualTo(PushSettingGroup.GENERAL);
        assertThat(NotificationType.SAFETY_SELF_CHECK.centerFilter()).isNull();
        assertThat(NotificationType.SAFETY_SELF_CHECK.settingGroup()).isEqualTo(PushSettingGroup.NONE);
        assertThat(NotificationType.FAMILY_LEADER_CHANGED.centerFilter()).isNull();
    }

    @Test
    @DisplayName("센터 전용과 동기화 타입을 조회하면 푸시 전달 정책을 구분한다")
    void 센터_전용과_동기화_타입을_조회하면_전달_정책을_구분한다() {
        // given / when / then
        assertThat(NotificationType.ALBUM_LIKED.deliveryMode()).isEqualTo(DeliveryMode.CENTER_ONLY);
        assertThat(NotificationType.ALBUM_LIKED.foregroundPresentation()).isEqualTo("NONE");
        assertThat(NotificationType.ALBUM_UPLOAD_COMPLETE.deliveryMode()).isEqualTo(DeliveryMode.PUSH_ONLY);
        assertThat(NotificationType.MEDICATION_SCHEDULE_SYNC.deliveryMode()).isEqualTo(DeliveryMode.DATA_ONLY);
        assertThat(NotificationType.MEDICATION_SCHEDULE_SYNC.deepLinkTemplate()).isNull();
    }

    @Test
    @DisplayName("레지스트리의 wire 타입을 조회하면 네 일정 이벤트만 구 앱 값을 반환한다")
    void 레지스트리의_wire_타입을_조회하면_네_일정_이벤트만_구_앱_값을_반환한다() {
        // given / when
        NotificationType[] legacyTypes = {
                NotificationType.MEDICATION_SCHEDULE_CREATED,
                NotificationType.MEDICATION_SCHEDULE_CHANGED,
                NotificationType.MEDICATION_SCHEDULE_DELETED,
                NotificationType.MEDICATION_SCHEDULE_SYNC
        };

        // then
        assertThat(legacyTypes).allSatisfy(type -> {
            assertThat(type.legacyDataType()).isEqualTo("MEDICATION_SCHEDULE_CHANGED");
            assertThat(type.dataTypeValue()).isEqualTo("MEDICATION_SCHEDULE_CHANGED");
        });
        assertThat(Arrays.stream(NotificationType.values()).filter(type -> !Arrays.asList(legacyTypes).contains(type)))
                .allSatisfy(type -> {
                    assertThat(type.legacyDataType()).isNull();
                    assertThat(type.dataTypeValue()).isEqualTo(type.name());
                });
    }

    @Test
    @DisplayName("우선순위를 조회하면 iOS 표시 수준을 구분한다")
    void 우선순위를_조회하면_iOS_표시_수준을_구분한다() {
        // given / when / then
        assertThat(NotificationPriority.CRITICAL.interruptionLevel()).isEqualTo("time-sensitive");
        assertThat(NotificationPriority.TIME_SENSITIVE.interruptionLevel()).isEqualTo("time-sensitive");
        assertThat(NotificationPriority.INTERACTION.interruptionLevel()).isEqualTo("active");
        assertThat(NotificationPriority.PASSIVE.interruptionLevel()).isEqualTo("passive");
    }
}
