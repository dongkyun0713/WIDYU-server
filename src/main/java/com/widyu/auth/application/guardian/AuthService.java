package com.widyu.auth.application.guardian;

import com.widyu.auth.application.LogoutService;
import com.widyu.auth.application.SmsService;
import com.widyu.auth.application.TemporaryTokenService;
import com.widyu.auth.application.VerificationCodeService;
import com.widyu.auth.application.guardian.local.LocalLoginService;
import com.widyu.auth.application.guardian.oauth.SocialLoginService;
import com.widyu.auth.domain.TemporaryMember;
import com.widyu.auth.dto.RefreshTokenDto;
import com.widyu.auth.dto.TemporaryTokenDto;
import com.widyu.auth.dto.request.AppleSignUpRequest;
import com.widyu.auth.dto.request.ChangePasswordRequest;
import com.widyu.auth.dto.request.EmailCheckRequest;
import com.widyu.auth.dto.request.FindPasswordRequest;
import com.widyu.auth.dto.request.LocalGuardianSignInRequest;
import com.widyu.auth.dto.request.LocalGuardianSignupRequest;
import com.widyu.auth.dto.request.RefreshTokenRequest;
import com.widyu.auth.dto.request.SmsCodeRequest;
import com.widyu.auth.dto.request.SmsVerificationRequest;
import com.widyu.auth.dto.request.SocialLoginRequest;
import com.widyu.auth.dto.response.MemberInfoResponse;
import com.widyu.auth.dto.response.SocialLoginResponse;
import com.widyu.auth.dto.response.TemporaryTokenResponse;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.auth.dto.response.UserProfile;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.domain.Member;
import com.widyu.member.domain.MemberRole;
import com.widyu.member.repository.MemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final SmsService smsService;
    private final VerificationCodeService verificationCodeService;
    private final TemporaryTokenService temporaryTokenService;
    private final LocalLoginService localLoginService;
    private final SocialLoginService socialLoginService;
    private final JwtTokenProvider jwtTokenProvider;
    private final MemberUtil memberUtil;
    private final MemberRepository memberRepository;
    private final LogoutService logoutService;

    @Transactional
    public void sendSmsVerification(final SmsVerificationRequest request) {
        smsService.sendVerificationSms(request.phoneNumber(), request.name());
    }

    @Transactional
    public void sendSmsVerificationIfMemberExist(final FindPasswordRequest request) {
        memberRepository.findByPhoneNumberAndNameAndLocalAccount_Email(request.phoneNumber(), request.name(),
                        request.email())
                .orElseThrow(() -> new IllegalArgumentException("일치하는 회원이 없습니다."));
        smsService.sendVerificationSms(request.phoneNumber(), request.name());
    }

    @Transactional
    public TemporaryTokenResponse verifySmsCode(final SmsCodeRequest request) {
        return verificationCodeService.verifyAndIssueTemporaryToken(request.phoneNumber(), request.code());
    }

    @Transactional(readOnly = true)
    public boolean isEmailRegistered(EmailCheckRequest request) {
        return localLoginService.isEmailRegistered(request);
    }

    @Transactional
    public TokenPairResponse localGuardianSignup(HttpServletRequest httpServletRequest,
                                                 final LocalGuardianSignupRequest request) {
        String tempToken = temporaryTokenService.extractFrom(httpServletRequest);
        TemporaryTokenDto dto = temporaryTokenService.parseAndValidate(tempToken);
        TemporaryMember temp = temporaryTokenService.loadTemporaryMemberOrThrow(dto.temporaryMemberId());

        TokenPairResponse tokens = localLoginService.signupGuardianWithLocal(temp, request.email(), request.password());

        temporaryTokenService.deleteTemporaryMember(temp.getId());
        return tokens;
    }

    @Transactional
    public TokenPairResponse localGuardianSignIn(LocalGuardianSignInRequest request) {
        return localLoginService.signIn(request);
    }

    @Transactional
    public SocialLoginResponse socialLogin(String provider, SocialLoginRequest request) {
        return socialLoginService.socialLogin(provider, request);
    }

    @Transactional
    public void updatePhoneNumberIfAppleSignUp(AppleSignUpRequest request, HttpServletRequest httpServletRequest) {
        socialLoginService.updatePhoneNumberIfAppleSignUp(request, httpServletRequest);
    }

    @Transactional(readOnly = true)
    public UserProfile getUserProfileByTemporaryToken(HttpServletRequest httpServletRequest) {
        return localLoginService.getUserProfileByTemporaryToken(httpServletRequest);
    }

    @Transactional(readOnly = true)
    public MemberInfoResponse findMemberByPhoneNumberAndName(SmsVerificationRequest request) {
        return localLoginService.findMemberByPhoneNumberAndName(request);
    }

    @Transactional(readOnly = true)
    public TokenPairResponse reissueTokenPair(RefreshTokenRequest request) {
        RefreshTokenDto refreshTokenDto =
                jwtTokenProvider.retrieveRefreshToken(request.refreshToken());
        RefreshTokenDto refreshToken =
                jwtTokenProvider.createRefreshTokenDto(refreshTokenDto.memberId());

        Member member = memberUtil.getMemberByMemberId(refreshToken.memberId());

        return jwtTokenProvider.generateTokenPair(member.getId(), MemberRole.USER);
    }

    @Transactional
    public boolean changeMemberPassword(ChangePasswordRequest request, HttpServletRequest httpServletRequest) {
        return localLoginService.changePassword(request, httpServletRequest);
    }

    @Transactional(readOnly = true)
    public void logout() {
        logoutService.logout();
    }
}
