package com.widyu.auth.application.guardian;

import com.widyu.auth.application.SmsService;
import com.widyu.auth.application.TemporaryTokenService;
import com.widyu.auth.application.VerificationCodeService;
import com.widyu.auth.application.guardian.local.LocalLoginService;
import com.widyu.auth.application.guardian.oauth.SocialLoginService;
import com.widyu.auth.domain.OAuthProvider;
import com.widyu.auth.domain.TemporaryMember;
import com.widyu.auth.dto.TemporaryTokenDto;
import com.widyu.auth.dto.request.EmailCheckRequest;
import com.widyu.auth.dto.request.LocalGuardianSignInRequest;
import com.widyu.auth.dto.request.LocalGuardianSignupRequest;
import com.widyu.auth.dto.request.SmsCodeRequest;
import com.widyu.auth.dto.request.SmsVerificationRequest;
import com.widyu.auth.dto.request.SocialLoginRequest;
import com.widyu.auth.dto.response.MemberInfoResponse;
import com.widyu.auth.dto.response.OAuthTokenResponse;
import com.widyu.auth.dto.response.SocialClientResponse;
import com.widyu.auth.dto.response.SocialLoginResponse;
import com.widyu.auth.dto.response.TemporaryTokenResponse;
import com.widyu.auth.dto.response.TokenPairResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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

    @Transactional
    public void sendSmsVerification(final SmsVerificationRequest request) {
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
    public String redirectToSocialLogin(String provider, HttpServletResponse response) throws IOException {
        OAuthProvider oauthProvider = OAuthProvider.from(provider);
        return socialLoginService.redirectToOAuthProvider(oauthProvider, response);
    }

    @Transactional
    public SocialLoginResponse processSocialLoginCallback(String provider, String code, String state) {
        OAuthProvider oauthProvider = OAuthProvider.from(provider);

        OAuthTokenResponse oAuthTokenResponse = socialLoginService.getToken(oauthProvider, code, state);

        SocialClientResponse socialClientResponse = socialLoginService.authenticateFromProvider(
                oauthProvider, oAuthTokenResponse.accessToken());

        SocialLoginRequest socialLoginRequest = SocialLoginRequest.of(
                oauthProvider.getValue(),
                socialClientResponse.oauthId(),
                socialClientResponse.email(),
                socialClientResponse.name(),
                socialClientResponse.phoneNumber()
        );

        return socialLoginService.socialLogin(socialLoginRequest);
    }

    @Transactional(readOnly = true)
    public MemberInfoResponse findMemberByPhoneNumber(SmsVerificationRequest request) {
        return localLoginService.findMemberByPhoneNumber(request.phoneNumber());
    }
}
