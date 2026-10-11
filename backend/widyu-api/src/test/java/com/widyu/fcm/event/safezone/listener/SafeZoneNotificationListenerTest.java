package com.widyu.fcm.event.safezone.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.event.safezone.dto.SafeZoneEnterEvent;
import com.widyu.fcm.event.safezone.dto.SafeZoneExitEvent;
import com.widyu.global.entity.Status;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SafeZoneNotificationListenerTest {
    @Mock private MemberRepository members;
    @Mock private SeniorProfileRepository seniors;
    @Mock private FamilyMembershipRepository memberships;
    @Mock private FcmOutboxService outbox;
    @InjectMocks private SafeZoneNotificationListener listener;

    @Test
    @DisplayName("안심구역을 이탈하면 활성 보호자 전원에게 제목만 있는 Z01을 예약한다")
    void 안심구역을_이탈하면_활성_보호자에게_Z01을_예약한다() {
        // given
        givenRecipients();

        // when
        listener.handleSafeZoneExit(new SafeZoneExitEvent(1L));

        // then
        ArgumentCaptor<FcmSendDto> messages = ArgumentCaptor.forClass(FcmSendDto.class);
        then(outbox).should().enqueue(eq(2L), messages.capture());
        then(outbox).should().enqueue(eq(3L), messages.capture());
        then(outbox).should(times(2)).enqueue(any(), any());
        assertMessage(messages.getAllValues(), NotificationType.SAFE_ZONE_EXITED,
                "시니어 님이 안심구역을 벗어났어요.");
    }

    @Test
    @DisplayName("안심구역으로 돌아오면 활성 보호자 전원에게 제목만 있는 Z02를 예약한다")
    void 안심구역으로_돌아오면_활성_보호자에게_Z02를_예약한다() {
        // given
        givenRecipients();

        // when
        listener.handleSafeZoneEnter(new SafeZoneEnterEvent(1L));

        // then
        ArgumentCaptor<FcmSendDto> messages = ArgumentCaptor.forClass(FcmSendDto.class);
        then(outbox).should().enqueue(eq(2L), messages.capture());
        then(outbox).should().enqueue(eq(3L), messages.capture());
        assertMessage(messages.getAllValues(), NotificationType.SAFE_ZONE_ENTERED,
                "시니어 님이 안심구역으로 돌아왔어요.");
    }

    private void givenRecipients() {
        Member senior = org.mockito.Mockito.mock(Member.class);
        Member firstGuardian = org.mockito.Mockito.mock(Member.class);
        Member secondGuardian = org.mockito.Mockito.mock(Member.class);
        FamilyMembership first = org.mockito.Mockito.mock(FamilyMembership.class);
        FamilyMembership second = org.mockito.Mockito.mock(FamilyMembership.class);
        given(members.findById(1L)).willReturn(Optional.of(senior));
        given(seniors.findFamilyIdByMemberId(1L)).willReturn(Optional.of(10L));
        given(senior.getStatus()).willReturn(Status.ACTIVE);
        given(senior.getName()).willReturn("시니어");
        given(memberships.findAllByFamilyIdWithGuardian(10L)).willReturn(List.of(first, second));
        given(first.getGuardian()).willReturn(firstGuardian);
        given(second.getGuardian()).willReturn(secondGuardian);
        given(firstGuardian.getStatus()).willReturn(Status.ACTIVE);
        given(secondGuardian.getStatus()).willReturn(Status.ACTIVE);
        given(firstGuardian.getId()).willReturn(2L);
        given(secondGuardian.getId()).willReturn(3L);
    }

    private void assertMessage(List<FcmSendDto> messages, NotificationType type, String title) {
        assertThat(messages).hasSize(2);
        assertThat(messages).allSatisfy(message -> {
            assertThat(message.notificationType()).isEqualTo(type);
            assertThat(message.title()).isEqualTo(title);
            assertThat(message.content()).isNull();
            assertThat(message.seniorId()).isEqualTo(1L);
            assertThat(message.relatedMemberId()).isEqualTo(1L);
            assertThat(message.emergency()).isFalse();
            assertThat(message.dataForEnqueue(message.eventId()))
                    .containsEntry("deepLink", "/location?seniorId=1")
                    .doesNotContainKeys("deliveryStage", "safetyEventId");
        });
        assertThat(messages.get(0).eventId()).isEqualTo(messages.get(1).eventId());
        assertThat(UUID.fromString(messages.get(0).eventId())).isNotNull();
    }
}
