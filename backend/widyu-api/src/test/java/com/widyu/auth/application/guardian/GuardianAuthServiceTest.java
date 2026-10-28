package com.widyu.auth.application.guardian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.widyu.auth.TemporaryMember;
import com.widyu.auth.application.LogoutService;
import com.widyu.auth.application.TemporaryTokenService;
import com.widyu.auth.application.guardian.local.LocalLoginService;
import com.widyu.auth.application.guardian.oauth.SocialLoginService;
import com.widyu.auth.dto.TemporaryTokenDto;
import com.widyu.auth.dto.request.LocalGuardianSignupRequest;
import com.widyu.auth.dto.response.CurrentMemberResponse;
import com.widyu.auth.dto.response.LocalSignupResponse;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.FamilyMembership;
import com.widyu.member.LocalAccount;
import com.widyu.member.Member;
import com.widyu.member.MemberRole;
import com.widyu.member.SocialAccount;
import com.widyu.member.repository.FamilyMembershipRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("GuardianAuthService 단위 테스트")
class GuardianAuthServiceTest {

    @Mock private GuardianSmsService guardianSmsService;
    @Mock private GuardianTokenService guardianTokenService;
    @Mock private TemporaryTokenService temporaryTokenService;
    @Mock private LocalLoginService localLoginService;
    @Mock private SocialLoginService socialLoginService;
    @Mock private MemberWithdrawService memberWithdrawService;
    @Mock private LogoutService logoutService;
    @Mock private MemberUtil memberUtil;
    @Mock private FamilyMembershipRepository familyMembershipRepository;

    @InjectMocks
    private GuardianAuthService guardianAuthService;

    @Test
    @DisplayName("임시 토큰으로 보호자 가입 시 가입 응답이 반환되고 임시 회원이 삭제된다")
    void 임시_토큰으로_보호자_가입_시_응답_반환되고_임시회원_삭제된다() {
        // given
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        LocalGuardianSignupRequest request = new LocalGuardianSignupRequest("test@test.com", "Pass1234!", "홍길동", "01012341234");

        String tempToken = "temp-jwt-token";
        TemporaryTokenDto tokenDto = new TemporaryTokenDto("temp-id-uuid", MemberRole.USER, tempToken, 1800L);
        TemporaryMember temporaryMember = TemporaryMember.createTemporaryMember("홍길동", "01012341234");

        given(temporaryTokenService.extractFrom(httpRequest)).willReturn(tempToken);
        given(temporaryTokenService.parseAndValidate(tempToken)).willReturn(tokenDto);
        given(temporaryTokenService.loadTemporaryMemberOrThrow("temp-id-uuid")).willReturn(temporaryMember);

        LocalSignupResponse expectedResponse = mock(LocalSignupResponse.class);
        given(localLoginService.signupGuardianWithLocal(temporaryMember, "test@test.com", "Pass1234!"))
                .willReturn(expectedResponse);

        // when
        LocalSignupResponse response = guardianAuthService.localGuardianSignup(httpRequest, request);

        // then
        assertThat(response).isEqualTo(expectedResponse);
        verify(temporaryTokenService).deleteTemporaryMember(temporaryMember.getId());
    }

    @Test
    @DisplayName("로컬 계정 회원 조회 시 이메일과 로컬 프로바이더가 포함된 응답이 반환된다")
    void 로컬_계정_회원_조회_시_로컬_프로바이더_포함된_응답_반환된다() {
        // given
        Member currentMember = mock(Member.class);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);
        given(currentMember.getId()).willReturn(1L);

        LocalAccount localAccount = mock(LocalAccount.class);
        given(localAccount.getEmail()).willReturn("test@test.com");
        given(currentMember.getLocalAccount()).willReturn(localAccount);
        given(currentMember.getSocialAccounts()).willReturn(List.of());
        given(familyMembershipRepository.findByGuardianId(1L)).willReturn(Optional.empty());
        given(currentMember.getName()).willReturn("홍길동");
        given(currentMember.getPhoneNumber()).willReturn("01012341234");

        // when
        CurrentMemberResponse response = guardianAuthService.getCurrentMemberInfo();

        // then
        assertThat(response.memberId()).isEqualTo(1L);
        assertThat(response.phoneNumber()).isEqualTo("01012341234");
        assertThat(response.email()).isEqualTo("test@test.com");
        assertThat(response.providers()).contains("local");
        assertThat(response.hasParents()).isFalse();
    }

    @Test
    @DisplayName("소셜 계정 회원 조회 시 소셜 프로바이더가 포함된 응답이 반환된다")
    void 소셜_계정_회원_조회_시_소셜_프로바이더_포함된_응답_반환된다() {
        // given
        Member currentMember = mock(Member.class);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);
        given(currentMember.getId()).willReturn(1L);

        SocialAccount kakaoAccount = mock(SocialAccount.class);
        given(kakaoAccount.getProvider()).willReturn("kakao");
        given(kakaoAccount.getEmail()).willReturn("kakao@kakao.com");

        given(currentMember.getLocalAccount()).willReturn(null);
        given(currentMember.getSocialAccounts()).willReturn(List.of(kakaoAccount));
        given(familyMembershipRepository.findByGuardianId(1L)).willReturn(Optional.empty());
        given(currentMember.getName()).willReturn("홍길동");
        given(currentMember.getPhoneNumber()).willReturn("01012341234");

        // when
        CurrentMemberResponse response = guardianAuthService.getCurrentMemberInfo();

        // then
        assertThat(response.email()).isEqualTo("kakao@kakao.com");
        assertThat(response.providers()).contains("kakao");
        assertThat(response.providers()).doesNotContain("local");
    }

    @Test
    @DisplayName("가족 연결이 있는 회원 조회 시 hasParents가 true인 응답이 반환된다")
    void 가족_연결이_있는_회원_조회_시_hasParents가_true이다() {
        // given
        Member currentMember = mock(Member.class);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);
        given(currentMember.getId()).willReturn(1L);

        FamilyMembership membership = mock(FamilyMembership.class);
        given(currentMember.getLocalAccount()).willReturn(null);
        given(currentMember.getSocialAccounts()).willReturn(List.of());
        given(familyMembershipRepository.findByGuardianId(1L)).willReturn(Optional.of(membership));
        given(currentMember.getName()).willReturn("홍길동");
        given(currentMember.getPhoneNumber()).willReturn("01012341234");

        // when
        CurrentMemberResponse response = guardianAuthService.getCurrentMemberInfo();

        // then
        assertThat(response.hasParents()).isTrue();
    }
}
