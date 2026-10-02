package com.widyu.notification.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.widyu.fcm.FcmNotification;
import com.widyu.fcm.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationEnvelopeTest {

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
