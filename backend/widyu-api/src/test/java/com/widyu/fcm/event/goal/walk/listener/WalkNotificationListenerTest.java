package com.widyu.fcm.event.goal.walk.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.widyu.fcm.DeliveryMode;
import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.global.entity.Status;
import com.widyu.goal.walk.repository.WalkRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.SeniorProfileRepository;
import com.widyu.walk.Walk;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WalkNotificationListenerTest {

    @Mock private FcmService fcmService;
    @Mock private WalkRepository walkRepository;
    @Mock private SeniorProfileRepository seniorProfileRepository;
    @InjectMocks private WalkNotificationListener listener;

    @Test
    @DisplayName("양수 걸음으로 걷기 목표에 미달하면 W01 푸시를 한 건 만든다")
    void 양수_걸음으로_목표에_미달하면_W01_푸시를_만든다() {
        // given
        Walk walk = walk(6500);
        walk.getMember().updateProfileImage("https://cdn.widyu.store/profile/senior.png");
        given(walkRepository.findUnachievedWalksByDate(any())).willReturn(List.of(walk));
        ArgumentCaptor<FcmSendDto> captor = ArgumentCaptor.forClass(FcmSendDto.class);

        // when
        listener.sendWalkGoalReminderToUnachieved();

        // then
        then(fcmService).should().sendMessageToUser(any(), captor.capture());
        assertThat(captor.getValue().notificationType()).isEqualTo(NotificationType.WALK_GOAL_UNMET);
        assertThat(captor.getValue().notificationType().deliveryMode()).isEqualTo(DeliveryMode.PUSH_ONLY);
        assertThat(captor.getValue().title()).isEqualTo("오늘 6500걸음 걸으셨어요.");
        assertThat(captor.getValue().content()).isEqualTo("가능하다면 조금 더 걸어보는 건 어떨까요?");
        assertThat(captor.getValue().image()).isEqualTo("https://cdn.widyu.store/profile/senior.png");
        assertThat(captor.getValue().dataForEnqueue("event-1"))
                .containsEntry("deepLink", "/goal");
    }

    @Test
    @DisplayName("오늘 걸음 기록이 0걸음이어도 목표 미달이면 W01 푸시를 만든다")
    void 걸음_수가_0이어도_목표_미달이면_알림을_만든다() {
        // given
        given(walkRepository.findUnachievedWalksByDate(any())).willReturn(List.of(walk(0)));
        ArgumentCaptor<FcmSendDto> captor = ArgumentCaptor.forClass(FcmSendDto.class);

        // when
        listener.sendWalkGoalReminderToUnachieved();

        // then
        then(fcmService).should().sendMessageToUser(eq(1L), captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("오늘 0걸음 걸으셨어요.");
    }

    @Test
    @DisplayName("목표는 있는데 오늘 걸음 기록이 없는 시니어에게 0걸음 W01 푸시를 만든다")
    void 오늘_걸음_기록이_없는_목표_보유_시니어에게_0걸음_알림을_만든다() {
        // given
        Member senior = member(2L, MemberType.SENIOR);
        given(walkRepository.findUnachievedWalksByDate(any())).willReturn(List.of());
        given(seniorProfileRepository.findMembersWithWalkGoalButNoWalkOn(LocalDate.now(), Status.ACTIVE))
                .willReturn(List.of(senior));
        ArgumentCaptor<FcmSendDto> captor = ArgumentCaptor.forClass(FcmSendDto.class);

        // when
        listener.sendWalkGoalReminderToUnachieved();

        // then
        then(fcmService).should().sendMessageToUser(eq(2L), captor.capture());
        assertThat(captor.getValue().notificationType()).isEqualTo(NotificationType.WALK_GOAL_UNMET);
        assertThat(captor.getValue().title()).isEqualTo("오늘 0걸음 걸으셨어요.");
    }

    @Test
    @DisplayName("오늘 기록이 없는 대상이 시니어가 아니면 알림을 만들지 않는다")
    void 오늘_기록이_없는_대상이_시니어가_아니면_알림을_만들지_않는다() {
        // given
        given(walkRepository.findUnachievedWalksByDate(any())).willReturn(List.of());
        given(seniorProfileRepository.findMembersWithWalkGoalButNoWalkOn(LocalDate.now(), Status.ACTIVE))
                .willReturn(List.of(member(3L, MemberType.GUARDIAN)));

        // when
        listener.sendWalkGoalReminderToUnachieved();

        // then
        then(fcmService).should(never()).sendMessageToUser(anyLong(), any(FcmSendDto.class));
    }

    @Test
    @DisplayName("걷기 목표를 달성하면 걷기 목표 미달 알림을 만들지 않는다")
    void 걷기_목표를_달성하면_알림을_만들지_않는다() {
        // given
        given(walkRepository.findUnachievedWalksByDate(any())).willReturn(List.of(walk(10000)));

        // when
        listener.sendWalkGoalReminderToUnachieved();

        // then
        then(fcmService).should(never()).sendMessageToUser(anyLong(), any(FcmSendDto.class));
    }

    private Member member(Long id, MemberType type) {
        Member member = Member.createMember(type, "회원", "01011112222");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    private Walk walk(int steps) {
        Member member = member(1L, MemberType.SENIOR);
        Walk walk = Walk.createWithGoal(member, LocalDate.now(), 10000);
        walk.updateActualSteps(steps);
        return walk;
    }
}
