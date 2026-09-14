package com.widyu.heart.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.heart.dto.request.HeartMessageRequest;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("HeartMessageService 예외 처리 단위 테스트")
class HeartMessageServiceTest {

    @Mock private MemberUtil memberUtil;
    @Mock private MemberRepository memberRepository;
    @Mock private FamilyMembershipRepository familyMembershipRepository;
    @Mock private FcmService fcmService;

    @InjectMocks
    private HeartMessageService heartMessageService;

    @Test
    @DisplayName("가족에게 일반 하트를 보내면 긴급 표시 없이 발신자를 고정한다")
    void 가족에게_일반_하트를_보내면_발신자를_고정한다() {
        // given
        Member sender = org.mockito.Mockito.mock(Member.class);
        Member receiver = org.mockito.Mockito.mock(Member.class);
        com.widyu.member.SeniorProfile profile = org.mockito.Mockito.mock(com.widyu.member.SeniorProfile.class);
        given(memberUtil.getCurrentMember()).willReturn(sender);
        given(memberRepository.findById(2L)).willReturn(Optional.of(receiver));
        given(sender.getId()).willReturn(1L);
        given(sender.getType()).willReturn(MemberType.GUARDIAN);
        given(receiver.getId()).willReturn(2L);
        given(receiver.getType()).willReturn(MemberType.SENIOR);
        given(receiver.getSeniorProfile()).willReturn(profile);
        given(profile.getId()).willReturn(20L);
        given(familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(1L, 20L)).willReturn(true);
        org.mockito.ArgumentCaptor<FcmSendDto> captor = org.mockito.ArgumentCaptor.forClass(FcmSendDto.class);

        // when
        heartMessageService.sendHeartMessage(new HeartMessageRequest(2L, "응원해요"));

        // then
        then(fcmService).should().sendMessageToUser(org.mockito.ArgumentMatchers.eq(2L), captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().relatedMemberId()).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().emergency()).isFalse();
    }

    @Test
    @DisplayName("수신자를 찾을 수 없으면 MEMBER_NOT_FOUND 예외를 던지고 FCM을 전송하지 않는다")
    void 수신자를_찾을_수_없으면_예외가_발생한다() {
        // given
        Member sender = Member.createMember(MemberType.GUARDIAN, "보호자", "01011112222");
        given(memberUtil.getCurrentMember()).willReturn(sender);
        given(memberRepository.findById(2L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> heartMessageService.sendHeartMessage(new HeartMessageRequest(2L, "괜찮으세요?")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND)
                .hasMessageContaining("회원을 찾을 수 없습니다.");
        then(fcmService).should(never()).sendMessageToUser(anyLong(), any(FcmSendDto.class));
    }

    @Test
    @DisplayName("같은 유형 회원에게 하트 메시지를 보내면 FORBIDDEN 예외를 던지고 FCM을 전송하지 않는다")
    void 같은_유형_회원에게_보내면_예외가_발생한다() {
        // given
        Member sender = Member.createMember(MemberType.GUARDIAN, "보호자1", "01011112222");
        Member receiver = Member.createMember(MemberType.GUARDIAN, "보호자2", "01033334444");
        given(memberUtil.getCurrentMember()).willReturn(sender);
        given(memberRepository.findById(2L)).willReturn(Optional.of(receiver));

        // when & then
        assertThatThrownBy(() -> heartMessageService.sendHeartMessage(new HeartMessageRequest(2L, "괜찮으세요?")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN)
                .hasMessageContaining("접근 권한이 없습니다.");
        then(fcmService).should(never()).sendMessageToUser(anyLong(), any(FcmSendDto.class));
    }
}
