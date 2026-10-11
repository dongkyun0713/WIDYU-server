package com.widyu.goal.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.dto.FcmSendDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GuardianGoalChangedNotificationListenerTest {

    @Mock private FcmOutboxService outboxService;
    @InjectMocks private GuardianGoalChangedNotificationListener listener;

    @Test
    @DisplayName("보호자가 건강일정을 등록하면 시니어 한 명에게 H02 문구와 일정 딥링크를 enqueue한다")
    void 건강일정을_등록하면_시니어에게_H02를_enqueue한다() {
        // given
        GuardianGoalChangedEvent event = GuardianGoalChangedEvent.healthSchedule(
                2L, NotificationType.HEALTH_SCHEDULE_CREATED, 100L, "홍길동");

        // when
        listener.onGuardianGoalChanged(event);

        // then
        ArgumentCaptor<FcmSendDto> messages = ArgumentCaptor.forClass(FcmSendDto.class);
        then(outboxService).should(times(1)).enqueue(org.mockito.ArgumentMatchers.eq(2L), messages.capture());
        FcmSendDto message = messages.getValue();
        assertThat(message.notificationType()).isEqualTo(NotificationType.HEALTH_SCHEDULE_CREATED);
        assertThat(message.title()).isEqualTo("홍길동 님이 새 건강 일정을 등록했어요.");
        assertThat(message.content()).isEqualTo("앱에서 일정을 확인해보세요.");
        assertThat(message.dataForEnqueue("event-1"))
                .containsEntry("entityId", "100")
                .containsEntry("actorDisplayName", "홍길동")
                .containsEntry("deepLink", "/goal")
                .containsEntry("type", "HEALTH_SCHEDULE_CREATED")
                .containsEntry("eventId", "event-1");
    }

    @Test
    @DisplayName("보호자가 건강일정을 변경하면 시니어 한 명에게 H03 문구를 enqueue한다")
    void 건강일정을_변경하면_시니어에게_H03을_enqueue한다() {
        // given
        GuardianGoalChangedEvent event = GuardianGoalChangedEvent.healthSchedule(
                2L, NotificationType.HEALTH_SCHEDULE_UPDATED, 100L, "홍길동");

        // when
        listener.onGuardianGoalChanged(event);

        // then
        ArgumentCaptor<FcmSendDto> messages = ArgumentCaptor.forClass(FcmSendDto.class);
        then(outboxService).should(times(1)).enqueue(org.mockito.ArgumentMatchers.eq(2L), messages.capture());
        assertThat(messages.getValue().notificationType()).isEqualTo(NotificationType.HEALTH_SCHEDULE_UPDATED);
        assertThat(messages.getValue().title()).isEqualTo("홍길동 님이 건강 일정을 변경했어요.");
        assertThat(messages.getValue().dataForEnqueue("event-2"))
                .containsEntry("entityId", "100")
                .containsEntry("deepLink", "/goal");
    }

    @Test
    @DisplayName("보호자가 건강일정을 삭제하면 시니어 한 명에게 H04와 목표 딥링크를 enqueue한다")
    void 건강일정을_삭제하면_시니어에게_H04와_목표_딥링크를_enqueue한다() {
        // given
        GuardianGoalChangedEvent event = GuardianGoalChangedEvent.healthSchedule(
                2L, NotificationType.HEALTH_SCHEDULE_DELETED, 100L, "홍길동");

        // when
        listener.onGuardianGoalChanged(event);

        // then
        ArgumentCaptor<FcmSendDto> messages = ArgumentCaptor.forClass(FcmSendDto.class);
        then(outboxService).should(times(1)).enqueue(org.mockito.ArgumentMatchers.eq(2L), messages.capture());
        FcmSendDto message = messages.getValue();
        assertThat(message.notificationType()).isEqualTo(NotificationType.HEALTH_SCHEDULE_DELETED);
        assertThat(message.title()).isEqualTo("홍길동 님이 건강 일정을 삭제했어요.");
        assertThat(message.content()).isEqualTo("앱에서 남은 일정을 확인해보세요.");
        assertThat(message.dataForEnqueue("event-3"))
                .containsEntry("entityId", "100")
                .containsEntry("deepLink", "/goal");
    }

    @Test
    @DisplayName("보호자가 걷기 목표를 바꾸면 새 걸음 수가 든 W02를 시니어에게 enqueue한다")
    void 걷기_목표를_바꾸면_새_걸음_수가_든_W02를_enqueue한다() {
        // given
        GuardianGoalChangedEvent event = GuardianGoalChangedEvent.walkGoal(2L, 8000, "홍길동");

        // when
        listener.onGuardianGoalChanged(event);

        // then
        ArgumentCaptor<FcmSendDto> messages = ArgumentCaptor.forClass(FcmSendDto.class);
        then(outboxService).should(times(1)).enqueue(org.mockito.ArgumentMatchers.eq(2L), messages.capture());
        FcmSendDto message = messages.getValue();
        assertThat(message.notificationType()).isEqualTo(NotificationType.WALK_GOAL_CHANGED);
        assertThat(message.title()).isEqualTo("홍길동 님이 걷기 목표를 변경했어요.");
        assertThat(message.content()).isEqualTo("새 목표는 하루 8000걸음이에요.");
        assertThat(message.dataForEnqueue("event-4"))
                .containsEntry("deepLink", "/goal")
                .doesNotContainKey("entityId");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("보호자 이름이 없으면 제목과 data의 이름을 가족으로 넣는다")
    void 보호자_이름이_없으면_가족으로_넣는다(String actorName) {
        // given
        GuardianGoalChangedEvent event = GuardianGoalChangedEvent.walkGoal(2L, 6000, actorName);

        // when
        listener.onGuardianGoalChanged(event);

        // then
        ArgumentCaptor<FcmSendDto> messages = ArgumentCaptor.forClass(FcmSendDto.class);
        then(outboxService).should(times(1)).enqueue(org.mockito.ArgumentMatchers.eq(2L), messages.capture());
        assertThat(messages.getValue().title()).isEqualTo("가족 님이 걷기 목표를 변경했어요.");
        assertThat(messages.getValue().dataForEnqueue("event-5"))
                .containsEntry("actorDisplayName", "가족");
    }
}
