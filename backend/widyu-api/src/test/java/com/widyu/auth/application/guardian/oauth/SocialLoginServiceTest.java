package com.widyu.auth.application.guardian.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import com.widyu.auth.OAuthProvider;
import com.widyu.auth.TemporaryMember;
import com.widyu.auth.application.SocialTemporaryTokenService;
import com.widyu.auth.application.guardian.oauth.strategy.SocialLoginStrategy;
import com.widyu.auth.application.guardian.oauth.strategy.SocialLoginStrategyFactory;
import com.widyu.auth.application.guardian.oauth.strategy.UserInfo;
import com.widyu.auth.dto.SocialTemporaryTokenDto;
import com.widyu.auth.dto.request.AppleSignUpRequest;
import com.widyu.auth.dto.request.SocialLoginRequest;
import com.widyu.auth.dto.response.SocialClientResponse;
import com.widyu.auth.dto.response.SocialLoginResponse;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.global.util.TemporaryMemberUtil;
import com.widyu.member.Member;
import com.widyu.member.MemberRole;
import com.widyu.member.MemberType;
import com.widyu.member.SocialAccount;
import com.widyu.member.repository.MemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("SocialLoginService 단위 테스트")
class SocialLoginServiceTest {

    @Mock private SocialLoginStrategyFactory strategyFactory;
    @Mock private MemberRepository memberRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private TemporaryMemberUtil temporaryMemberUtil;
    @Mock private SocialTemporaryTokenService socialTemporaryTokenService;
    @Mock private com.widyu.global.security.MemberSessionService memberSessionService;

    @InjectMocks
    private SocialLoginService socialLoginService;

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"INACTIVE", "DELETED"})
    @DisplayName("정지 또는 탈퇴 회원이 소셜 재로그인하면 재활성화와 발급을 거절한다")
    void 비활성_회원의_소셜_재로그인을_거절한다(Status status) {
        // given
        SocialLoginRequest request = mock(SocialLoginRequest.class);
        SocialLoginStrategy strategy = mock(SocialLoginStrategy.class);
        given(strategyFactory.getStrategy("kakao")).willReturn(strategy);
        given(strategy.getSupportedProvider()).willReturn(OAuthProvider.KAKAO);
        SocialClientResponse response = mock(SocialClientResponse.class);
        given(response.oauthId()).willReturn("oauth-inactive");
        given(strategy.getUserInfo(request)).willReturn(response);
        given(strategy.enrichWithRefreshToken(response, request)).willReturn(response);
        UserInfo info = mock(UserInfo.class);
        given(strategy.processUserInfo(response, request)).willReturn(info);
        Member member = Member.createMember(MemberType.GUARDIAN, "회원", "01012345678");
        ReflectionTestUtils.setField(member, "status", status);
        given(memberRepository.findMemberIdByProviderAndOauthId("kakao", "oauth-inactive")).willReturn(Optional.of(1L));
        given(memberRepository.findWithAllAccountsById(1L)).willReturn(Optional.of(member));
        given(memberSessionService.lock(member.getId())).willReturn(member);
        // when / then
        assertThatThrownBy(() -> socialLoginService.socialLogin("kakao", request)).isInstanceOf(BusinessException.class);
        assertThat(member.getStatus()).isEqualTo(status);
        Mockito.verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    @DisplayName("소셜 임시 토큰 헤더 없이 계정 연동 시도 시 BusinessException을 던진다")
    void 소셜_임시_토큰_헤더_없이_연동_시도_시_예외가_발생한다() {
        // given
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        given(httpRequest.getHeader(AUTHORIZATION)).willReturn(null);

        // when & then
        assertThatThrownBy(() -> socialLoginService.integrateSocialAccount(httpRequest))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MISSING_SOCIAL_TEMPORARY_TOKEN);
    }

    @Test
    @DisplayName("이미 연동된 소셜 제공자 계정 연동 시도 시 BusinessException을 던진다")
    void 이미_연동된_소셜_제공자_연동_시도_시_예외가_발생한다() {
        // given
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        given(httpRequest.getHeader(AUTHORIZATION)).willReturn("Bearer social-temp-token");

        SocialTemporaryTokenDto tokenDto = new SocialTemporaryTokenDto(1L, "kakao", "oauth123", "test@kakao.com");
        given(socialTemporaryTokenService.validateAndRetrieve("social-temp-token")).willReturn(tokenDto);

        Member member = mock(Member.class);
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        SocialAccount kakaoAccount = mock(SocialAccount.class);
        given(kakaoAccount.getProvider()).willReturn("kakao");
        given(member.getSocialAccounts()).willReturn(List.of(kakaoAccount));

        // when & then
        assertThatThrownBy(() -> socialLoginService.integrateSocialAccount(httpRequest))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SOCIAL_PROVIDER_ALREADY_LINKED);
    }

    @Test
    @DisplayName("유효한 소셜 임시 토큰으로 계정 연동 시 토큰 쌍이 반환된다")
    void 유효한_소셜_임시_토큰으로_계정_연동_시_토큰_쌍이_반환된다() {
        // given
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        given(httpRequest.getHeader(AUTHORIZATION)).willReturn("Bearer social-temp-token");

        SocialTemporaryTokenDto tokenDto = new SocialTemporaryTokenDto(1L, "kakao", "oauth123", "test@kakao.com");
        given(socialTemporaryTokenService.validateAndRetrieve("social-temp-token")).willReturn(tokenDto);

        Member member = mock(Member.class);
        given(member.getId()).willReturn(1L);
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(member.getSocialAccounts()).willReturn(new ArrayList<>());

        given(memberRepository.findBySocialAccounts_EmailAndSocialAccounts_Provider("test@kakao.com", "kakao"))
                .willReturn(Optional.empty());

        TokenPairResponse tokenPair = TokenPairResponse.of(1L, "access-token", "refresh-token");
        given(jwtTokenProvider.generateTokenPair(1L, MemberRole.USER, "kakao")).willReturn(tokenPair);

        // when
        TokenPairResponse response = socialLoginService.integrateSocialAccount(httpRequest);

        // then
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        verify(socialTemporaryTokenService).deleteSocialTemporaryToken("social-temp-token");
    }

    @Test
    @DisplayName("애플 이메일로 회원 조회 후 임시 토큰의 전화번호로 업데이트한다")
    void 애플_회원_전화번호_업데이트_시_전화번호가_변경된다() {
        // given
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        AppleSignUpRequest request = new AppleSignUpRequest("apple@apple.com");

        Member appleMember = Member.createMember(MemberType.GUARDIAN, "애플유저", "01011111111");
        given(memberRepository.findBySocialAccounts_EmailAndSocialAccounts_Provider("apple@apple.com", "APPLE"))
                .willReturn(Optional.of(appleMember));

        TemporaryMember temporaryMember = TemporaryMember.createTemporaryMember("애플유저", "01099999999");
        given(temporaryMemberUtil.getTemporaryMemberFromRequest(httpRequest)).willReturn(temporaryMember);

        // when
        socialLoginService.updatePhoneNumberIfAppleSignUp(request, httpRequest);

        // then
        assertThat(appleMember.getPhoneNumber()).isEqualTo("01099999999");
    }

    @Test
    @DisplayName("존재하지 않는 애플 이메일로 전화번호 업데이트 시 BusinessException을 던진다")
    void 존재하지_않는_애플_이메일로_전화번호_업데이트_시_예외가_발생한다() {
        // given
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        AppleSignUpRequest request = new AppleSignUpRequest("none@apple.com");

        given(memberRepository.findBySocialAccounts_EmailAndSocialAccounts_Provider("none@apple.com", "APPLE"))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> socialLoginService.updatePhoneNumberIfAppleSignUp(request, httpRequest))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    @DisplayName("기존 소셜 회원 로그인 시 토큰 쌍이 반환된다")
    void 기존_소셜_회원_로그인_시_토큰_쌍이_반환된다() {
        // given
        SocialLoginRequest request = mock(SocialLoginRequest.class);

        SocialLoginStrategy strategy = mock(SocialLoginStrategy.class);
        given(strategyFactory.getStrategy("kakao")).willReturn(strategy);
        given(strategy.getSupportedProvider()).willReturn(OAuthProvider.KAKAO);

        SocialClientResponse socialResponse = mock(SocialClientResponse.class);
        given(socialResponse.oauthId()).willReturn("oauth123");
        given(strategy.getUserInfo(request)).willReturn(socialResponse);
        given(strategy.enrichWithRefreshToken(socialResponse, request)).willReturn(socialResponse);

        UserInfo userInfo = mock(UserInfo.class);
        given(strategy.processUserInfo(socialResponse, request)).willReturn(userInfo);

        // 기존 회원 존재
        Member existingMember = mock(Member.class);
        given(existingMember.getId()).willReturn(1L);
        given(memberRepository.findMemberIdByProviderAndOauthId("kakao", "oauth123"))
                .willReturn(Optional.of(1L));
        given(memberRepository.findWithAllAccountsById(1L)).willReturn(Optional.of(existingMember));
        given(memberSessionService.lock(1L)).willReturn(existingMember);

        SocialAccount kakaoAccount = mock(SocialAccount.class);
        given(kakaoAccount.getProvider()).willReturn("kakao");
        given(kakaoAccount.isFirst()).willReturn(false);
        given(kakaoAccount.getOauthId()).willReturn("oauth123");
        given(existingMember.getSocialAccounts()).willReturn(List.of(kakaoAccount));
        given(existingMember.getSocialAccount("kakao")).willReturn(kakaoAccount);

        TokenPairResponse tokenPair = TokenPairResponse.of(1L, "access-token", "refresh-token");
        given(jwtTokenProvider.generateTokenPair(1L, MemberRole.USER, "kakao")).willReturn(tokenPair);

        // when
        SocialLoginResponse response = socialLoginService.socialLogin("kakao", request);

        // then
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.socialTemporaryToken()).isNull();
    }
}
