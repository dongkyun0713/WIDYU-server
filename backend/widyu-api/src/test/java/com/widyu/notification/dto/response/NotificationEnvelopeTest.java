package com.widyu.notification.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.widyu.fcm.FcmNotification;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.NotificationType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@ExtendWith(MockitoExtension.class)
class NotificationEnvelopeTest {

    @ParameterizedTest
    @EnumSource(NotificationType.class)
    @DisplayName("상세 정책 타입의 센터 행을 직렬화하면 기존 카테고리만 반환한다")
    void 상세_정책_타입의_센터_행은_기존_카테고리만_반환한다(NotificationType type) {
        // given
        FcmNotification stored = FcmNotification.builder().id(8111L).type(type)
                .fcmCategory(type.fcmCategory()).eventId("same-event")
                .deepLink("/goal").isRead(false).build();

        // when
        JsonNode json = new ObjectMapper().valueToTree(NotificationEnvelope.from(stored));

        // then
        assertThat(json.has("type")).isFalse();
        assertThat(json.has("notificationType")).isFalse();
        assertThat(json.path("category").asText()).isEqualTo(type.fcmCategory().name());
        assertThat(json.path("notificationId").asLong()).isEqualTo(8111L);
        assertThat(json.path("deepLink").asText()).isEqualTo("/goal");
        assertThat(json.path("readAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("기존 센터 행에 카테고리만 있으면 그 저장값을 반환한다")
    void 기존_센터_행은_카테고리_저장값을_반환한다() {
        // given
        FcmNotification stored = FcmNotification.builder().id(1L).fcmCategory(FcmCategory.SAFE_ZONE).build();
        FcmNotification missing = FcmNotification.builder().id(2L).build();

        // when
        NotificationEnvelope response = NotificationEnvelope.from(stored);
        NotificationEnvelope fallback = NotificationEnvelope.from(missing);

        // then
        assertThat(response.category()).isEqualTo("SAFE_ZONE");
        assertThat(fallback.category()).isEqualTo("ALL");
    }

    @Test
    @DisplayName("센터 행을 응답으로 변환하면 앨범 해금 필드를 저장값대로 싣고 값이 없는 행은 null을 반환한다")
    void 센터_행을_변환하면_앨범_해금_필드를_저장값대로_반환한다() {
        // given
        FcmNotification unlocked = FcmNotification.builder()
                .id(1L)
                .type(NotificationType.ALBUM_UNLOCKED)
                .seniorDisplayName("민수")
                .remainingLockedCount(0)
                .build();
        FcmNotification other = FcmNotification.builder()
                .id(2L)
                .type(NotificationType.ALBUM_CREATED)
                .build();
        FcmNotification legacyWithValues = FcmNotification.builder()
                .id(3L)
                .seniorDisplayName("영희")
                .remainingLockedCount(2)
                .build();
        FcmNotification legacyWithoutValues = FcmNotification.builder()
                .id(4L)
                .build();

        // when
        NotificationEnvelope unlockedEnvelope = NotificationEnvelope.from(unlocked);
        NotificationEnvelope otherEnvelope = NotificationEnvelope.from(other);
        NotificationEnvelope legacyWithValuesEnvelope = NotificationEnvelope.from(legacyWithValues);
        NotificationEnvelope legacyWithoutValuesEnvelope = NotificationEnvelope.from(legacyWithoutValues);

        // then
        assertThat(unlockedEnvelope.seniorDisplayName()).isEqualTo("민수");
        assertThat(unlockedEnvelope.remainingLockedCount()).isZero();
        assertThat(otherEnvelope.seniorDisplayName()).isNull();
        assertThat(otherEnvelope.remainingLockedCount()).isNull();
        assertThat(legacyWithValuesEnvelope.seniorDisplayName()).isEqualTo("영희");
        assertThat(legacyWithValuesEnvelope.remainingLockedCount()).isEqualTo(2);
        assertThat(legacyWithoutValuesEnvelope.seniorDisplayName()).isNull();
        assertThat(legacyWithoutValuesEnvelope.remainingLockedCount()).isNull();
    }
}
