package com.widyu.heart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.event.heart.dto.HeartRateEmergencyEvent;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("HeartRateEmergencyNotificationService 단위 테스트")
class HeartRateEmergencyNotificationServiceTest {

    @Mock private FcmService fcmService;
    @Mock private MemberRepository memberRepository;
    @Mock private FamilyMembershipRepository familyMembershipRepository;
    @Mock private SeniorProfileRepository seniorProfileRepository;

    @InjectMocks
    private HeartRateEmergencyNotificationService heartRateEmergencyNotificationService;

    @Test
    @DisplayName("심박 긴급 상태가 발생하면 가족 보호자에게 알림을 발송한다")
    void 심박_긴급_상태가_발생하면_가족_보호자에게_알림을_발송한다() {
        // given
        Member senior = mock(Member.class);
        FamilyMembership membership = mock(FamilyMembership.class);
        Member guardian = mock(Member.class);
        ArgumentCaptor<FcmSendDto> notificationCaptor = ArgumentCaptor.forClass(FcmSendDto.class);

        given(memberRepository.findById(1L)).willReturn(Optional.of(senior));
        given(seniorProfileRepository.findFamilyIdByMemberId(1L)).willReturn(Optional.of(10L));
        given(familyMembershipRepository.findAllByFamilyIdWithGuardian(10L)).willReturn(List.of(membership));
        given(membership.getGuardian()).willReturn(guardian);
        given(guardian.getId()).willReturn(2L);
        given(senior.getName()).willReturn("시니어");
        given(senior.getProfileImage()).willReturn("profile-image");

        // when
        heartRateEmergencyNotificationService.handleHeartRateEmergency(new HeartRateEmergencyEvent(1L, null));

        // then
        then(fcmService).should().sendMessageToUser(eq(2L), notificationCaptor.capture());
        FcmSendDto notification = notificationCaptor.getValue();
        assertThat(notification.title()).isEqualTo("시니어님의 심박수 이상이 감지되었습니다");
        assertThat(notification.content()).isEqualTo("현재 상태를 확인해주세요.");
        assertThat(notification.fcmCategory()).isEqualTo(FcmCategory.HEART_MESSAGE);
        assertThat(notification.emergency()).isTrue();
        assertThat(notification.relatedMemberId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("보호자 알림 저장이 실패하면 업무 롤백을 위해 예외를 전파한다")
    void 보호자_알림_저장이_실패하면_예외를_전파한다() {
        // given
        Member senior = mock(Member.class);
        FamilyMembership firstMembership = guardianMembership(2L);
        FamilyMembership secondMembership = mock(FamilyMembership.class);

        given(memberRepository.findById(1L)).willReturn(Optional.of(senior));
        given(seniorProfileRepository.findFamilyIdByMemberId(1L)).willReturn(Optional.of(10L));
        given(familyMembershipRepository.findAllByFamilyIdWithGuardian(10L))
                .willReturn(List.of(firstMembership, secondMembership));
        given(senior.getName()).willReturn("시니어");
        given(senior.getProfileImage()).willReturn("profile-image");
        willThrow(new RuntimeException("FCM 실패"))
                .given(fcmService).sendMessageToUser(eq(2L), any());

        // when & then
        assertThatThrownBy(() ->
                heartRateEmergencyNotificationService.handleHeartRateEmergency(new HeartRateEmergencyEvent(1L, null)))
                .isInstanceOf(RuntimeException.class).hasMessage("FCM 실패");
        then(fcmService).should().sendMessageToUser(eq(2L), any());
        then(secondMembership).should(never()).getGuardian();
    }

    private FamilyMembership guardianMembership(Long guardianId) {
        FamilyMembership membership = mock(FamilyMembership.class);
        Member guardian = mock(Member.class);
        given(membership.getGuardian()).willReturn(guardian);
        given(guardian.getId()).willReturn(guardianId);
        return membership;
    }
}
