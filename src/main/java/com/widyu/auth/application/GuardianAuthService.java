package com.widyu.auth.application;

import com.widyu.auth.domain.TemporaryMember;
import com.widyu.auth.dto.TemporaryTokenDto;
import com.widyu.auth.dto.request.EmailCheckRequest;
import com.widyu.auth.dto.request.LocalGuardianSignInRequest;
import com.widyu.auth.dto.request.LocalGuardianSignupRequest;
import com.widyu.auth.dto.request.SmsCodeRequest;
import com.widyu.auth.dto.request.SmsVerificationRequest;
import com.widyu.auth.dto.response.TemporaryTokenResponse;
import com.widyu.auth.dto.response.TokenPairResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GuardianAuthService {

    private final SmsService smsService;
    private final VerificationCodeService verificationCodeService;
    private final TemporaryTokenService temporaryTokenService;
    private final LocalGuardianAuthService localGuardianAuthService;


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
        return localGuardianAuthService.isEmailRegistered(request);
    }

    // 로컬 보호자 회원가입
    @Transactional
    public TokenPairResponse localGuardianSignup(HttpServletRequest httpServletRequest,
                                                 final LocalGuardianSignupRequest request) {
        String tempToken = temporaryTokenService.extractFrom(httpServletRequest);
        TemporaryTokenDto dto = temporaryTokenService.parseAndValidate(tempToken);
        TemporaryMember temp = temporaryTokenService.loadTemporaryMemberOrThrow(dto.temporaryMemberId());

        TokenPairResponse tokens = localGuardianAuthService.signupGuardianWithLocal(temp, request.email(), request.password());

        temporaryTokenService.deleteTemporaryMember(temp.getId());
        return tokens;
    }

    @Transactional
    public TokenPairResponse localGuardianSignIn(LocalGuardianSignInRequest request) {
        return localGuardianAuthService.signIn(request);
    }
}
