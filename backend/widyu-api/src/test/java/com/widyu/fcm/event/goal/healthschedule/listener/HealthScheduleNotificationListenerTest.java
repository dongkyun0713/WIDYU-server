package com.widyu.fcm.event.goal.healthschedule.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.widyu.fcm.DeliveryMode;
import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.goal.healthschedule.repository.HealthScheduleRepository;
import com.widyu.healthschedule.HealthSchedule;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import java.time.LocalDateTime;
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
class HealthScheduleNotificationListenerTest {

    @Mock private FcmService fcmService;
    @Mock private HealthScheduleRepository healthScheduleRepository;
    @InjectMocks private HealthScheduleNotificationListener listener;

    @Test
    @DisplayName("시니어 일정이 임박하면 OS에는 일정명을 빼고 센터에는 일정명과 시각을 넣는다")
    void 시니어_일정이_임박하면_OS와_센터_문구를_분리한다() {
        // given
        HealthSchedule schedule = schedule(MemberType.SENIOR, 1L);
        schedule.getMember().updateProfileImage("https://cdn.widyu.store/profile/senior.png");
        given(healthScheduleRepository.findUpcomingSchedulesInTimeRange(any(), any()))
                .willReturn(List.of(schedule));
        ArgumentCaptor<FcmSendDto> captor = ArgumentCaptor.forClass(FcmSendDto.class);

        // when
        listener.sendHealthScheduleReminder();

        // then
        then(fcmService).should().sendMessageToUser(any(), captor.capture());
        FcmSendDto dto = captor.getValue();
        assertThat(dto.notificationType()).isEqualTo(NotificationType.HEALTH_SCHEDULE_UPCOMING);
        assertThat(dto.notificationType().deliveryMode()).isEqualTo(DeliveryMode.PUSH_AND_CENTER);
        assertThat(dto.title()).isEqualTo("건강 일정이 곧 있어요.");
        assertThat(dto.content()).isEqualTo("앱에서 일정 시간과 내용을 확인해주세요.");
        assertThat(dto.title()).doesNotContain("병원", "15:30");
        assertThat(dto.centerTitle()).isEqualTo("오후 3:30에 병원 진료 일정이 있어요.");
        assertThat(dto.centerBody()).isEqualTo("잊지 않도록 일정을 확인해보세요.");
        assertThat(dto.image()).isEqualTo("https://cdn.widyu.store/profile/senior.png");
        assertThat(dto.dataForEnqueue("event-1")).containsEntry("inAppTitle", dto.centerTitle());
    }

    @Test
    @DisplayName("보호자 소유 일정이 임박하면 H01을 만들지 않는다")
    void 보호자_소유_일정이_임박하면_H01을_만들지_않는다() {
        // given
        HealthSchedule schedule = schedule(MemberType.GUARDIAN, 2L);
        given(healthScheduleRepository.findUpcomingSchedulesInTimeRange(any(), any()))
                .willReturn(List.of(schedule));
        // when
        listener.sendHealthScheduleReminder();

        // then
        then(fcmService).should(never()).sendMessageToUser(any(), any());
    }

    @Test
    @DisplayName("10분마다 실행하면 15시대 일정이 시작 시각과 무관하게 각각 정확히 한 번 알림된다")
    void 십분마다_실행하면_15시대_일정이_각각_정확히_한_번_알림된다() {
        // given: 15:00·15:20·15:30과 경계(15:09:59, 15:10, 16:00) 일정. 16:00은 15시 실행 회차 몫이다.
        List<HealthSchedule> schedules = List.of(
                scheduleAt(101L, LocalDateTime.of(2026, 10, 22, 15, 0)),
                scheduleAt(102L, LocalDateTime.of(2026, 10, 22, 15, 9, 59)),
                scheduleAt(103L, LocalDateTime.of(2026, 10, 22, 15, 10)),
                scheduleAt(104L, LocalDateTime.of(2026, 10, 22, 15, 20)),
                scheduleAt(105L, LocalDateTime.of(2026, 10, 22, 15, 30)),
                scheduleAt(106L, LocalDateTime.of(2026, 10, 22, 16, 0)));
        given(healthScheduleRepository.findUpcomingSchedulesInTimeRange(any(), any())).willAnswer(invocation -> {
            LocalDateTime start = invocation.getArgument(0);
            LocalDateTime end = invocation.getArgument(1);
            return schedules.stream()
                    .filter(s -> !s.getScheduledAt().isBefore(start) && s.getScheduledAt().isBefore(end))
                    .toList();
        });
        ArgumentCaptor<Long> recipients = ArgumentCaptor.forClass(Long.class);

        // when: 스케줄러 지연으로 실행 시각에 밀리초가 붙어도 같은 창이어야 한다.
        listener.sendHealthScheduleReminderAt(LocalDateTime.of(2026, 10, 22, 14, 0, 0, 37_000_000));
        listener.sendHealthScheduleReminderAt(LocalDateTime.of(2026, 10, 22, 14, 10, 0, 2_000_000));
        listener.sendHealthScheduleReminderAt(LocalDateTime.of(2026, 10, 22, 14, 20, 0, 500_000_000));
        listener.sendHealthScheduleReminderAt(LocalDateTime.of(2026, 10, 22, 14, 30, 0, 1_000_000));
        listener.sendHealthScheduleReminderAt(LocalDateTime.of(2026, 10, 22, 14, 40, 0, 200_000_000));
        listener.sendHealthScheduleReminderAt(LocalDateTime.of(2026, 10, 22, 14, 50, 0, 999_000_000));

        // then
        then(fcmService).should(times(5)).sendMessageToUser(recipients.capture(), any());
        assertThat(recipients.getAllValues()).containsExactly(101L, 102L, 103L, 104L, 105L);
    }

    private HealthSchedule scheduleAt(Long memberId, LocalDateTime scheduledAt) {
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01011112222");
        ReflectionTestUtils.setField(member, "id", memberId);
        HealthSchedule schedule = HealthSchedule.create(member, "병원 진료", null, null, null, scheduledAt);
        ReflectionTestUtils.setField(schedule, "id", memberId);
        return schedule;
    }

    private HealthSchedule schedule(MemberType type, Long memberId) {
        Member member = Member.createMember(type, "당사자", "01011112222");
        ReflectionTestUtils.setField(member, "id", memberId);
        HealthSchedule schedule = HealthSchedule.create(member, "병원 진료", null, null, null,
                LocalDateTime.of(2026, 8, 25, 15, 30));
        ReflectionTestUtils.setField(schedule, "id", 10L);
        return schedule;
    }
}
