package com.widyu.mypage.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.dto.FcmSendDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FamilyLeaderChangedNotificationListenerTest {

    @Mock private FcmOutboxService outboxService;
    @InjectMocks private FamilyLeaderChangedNotificationListener listener;

    @Test
    @DisplayName("방장이 바뀌면 새 방장 한 명에게 R01 알림을 등록한다")
    void 방장이_바뀌면_새_방장에게_R01을_등록한다() {
        // given
        FamilyLeaderChangedEvent event = FamilyLeaderChangedEvent.of(27L);
        ArgumentCaptor<FcmSendDto> message = ArgumentCaptor.forClass(FcmSendDto.class);

        // when
        listener.onLeaderChanged(event);

        // then
        verify(outboxService).enqueue(org.mockito.ArgumentMatchers.eq(27L), message.capture());
        verifyNoMoreInteractions(outboxService);
        FcmSendDto sent = message.getValue();
        assertThat(sent.notificationType()).isEqualTo(NotificationType.FAMILY_LEADER_CHANGED);
        assertThat(sent.title()).isEqualTo("이제 가족 방장이 되었어요.");
        assertThat(sent.content()).isEqualTo("가족 관리와 중요한 알림을 확인해주세요.");
        assertThat(sent.scheme()).isEqualTo("/family-manage");
        assertThat(sent.dataForEnqueue("event-1"))
                .containsEntry("type", "FAMILY_LEADER_CHANGED")
                .containsEntry("deepLink", "/family-manage");
    }
}
