package com.widyu.admin.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.widyu.album.repository.AlbumRepository;
import com.widyu.fcm.repository.MemberFcmTokenRepository;
import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.MemberSessionService;
import com.widyu.heart.repository.HeartRateEmergencyRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import com.widyu.pay.repository.PaymentRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminMemberService 예외 처리 단위 테스트")
class AdminMemberServiceTest {

    @Mock private MemberRepository memberRepository;
    @Mock private MemberSessionService memberSessionService;
    @Mock private SeniorProfileRepository seniorProfileRepository;
    @Mock private FamilyMembershipRepository familyMembershipRepository;
    @Mock private MemberFcmTokenRepository memberFcmTokenRepository;
    @Mock private AlbumRepository albumRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private HeartRateEmergencyRepository heartRateEmergencyRepository;
    @Mock private AdminAuditLogService adminAuditLogService;

    @InjectMocks
    private AdminMemberService adminMemberService;

    @Test
    @DisplayName("존재하지 않는 회원 상세 조회 시 MEMBER_NOT_FOUND 예외를 던진다")
    void 존재하지_않는_회원_상세_조회_시_예외가_발생한다() {
        // given
        given(memberRepository.findById(1L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> adminMemberService.getMemberDetail(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND)
                .hasMessageContaining("회원을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("허용되지 않는 회원 상태 변경 시 FORBIDDEN 예외를 던진다")
    void 허용되지_않는_회원_상태_변경_시_예외가_발생한다() {
        // given
        Member member = Member.createMember(MemberType.GUARDIAN, "보호자", "01011112222");
        given(memberSessionService.revoke(1L)).willReturn(member);

        // when & then
        assertThatThrownBy(() -> adminMemberService.changeStatus(1L, Status.DELETED))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN)
                .hasMessageContaining("ACTIVE 또는 INACTIVE만 허용됩니다.");
    }
}
