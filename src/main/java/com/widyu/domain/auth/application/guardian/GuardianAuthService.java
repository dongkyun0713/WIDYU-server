package com.widyu.domain.auth.application.guardian;

import com.widyu.domain.auth.application.LogoutService;
import com.widyu.domain.auth.application.TemporaryTokenService;
import com.widyu.domain.auth.application.guardian.local.LocalLoginService;
import com.widyu.domain.auth.application.guardian.oauth.SocialLoginService;
import com.widyu.domain.auth.entity.TemporaryMember;
import com.widyu.domain.auth.dto.TemporaryTokenDto;
import com.widyu.domain.auth.dto.request.AppleSignUpRequest;
import com.widyu.domain.auth.dto.request.ChangePasswordRequest;
import com.widyu.domain.auth.dto.request.EmailCheckRequest;
import com.widyu.domain.auth.dto.request.FindPasswordRequest;
import com.widyu.domain.auth.dto.request.LocalGuardianSignInRequest;
import com.widyu.domain.auth.dto.request.LocalGuardianSignupRequest;
import com.widyu.domain.auth.dto.request.RefreshTokenRequest;
import com.widyu.domain.auth.dto.request.SmsCodeRequest;
import com.widyu.domain.auth.dto.request.MemberWithdrawRequest;
import com.widyu.domain.auth.dto.request.SmsVerificationRequest;
import com.widyu.domain.auth.dto.request.SocialIntegrationRequest;
import com.widyu.domain.auth.dto.request.SocialLoginRequest;
import com.widyu.domain.auth.dto.response.LocalSignupResponse;
import com.widyu.domain.auth.dto.response.MemberInfoResponse;
import com.widyu.domain.auth.dto.response.CurrentMemberResponse;
import com.widyu.domain.auth.dto.response.SocialLoginResponse;
import com.widyu.global.util.MemberUtil;
import com.widyu.domain.auth.dto.response.TemporaryTokenResponse;
import com.widyu.domain.auth.dto.response.TokenPairResponse;
import com.widyu.domain.auth.dto.response.UserProfile;
import com.widyu.domain.member.entity.Member;
import com.widyu.domain.member.entity.SocialAccount;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GuardianAuthService {
    public static final String PROVIDER_LOCAL = "local";

    private final GuardianSmsService guardianSmsService;
    private final GuardianTokenService guardianTokenService;
    private final TemporaryTokenService temporaryTokenService;
    private final LocalLoginService localLoginService;
    private final SocialLoginService socialLoginService;
    private final MemberWithdrawService memberWithdrawService;
    private final LogoutService logoutService;
    private final MemberUtil memberUtil;

    @Transactional
    public void sendSmsVerification(final SmsVerificationRequest request) {
        guardianSmsService.sendVerificationSms(request);
    }

    @Transactional
    public void sendSmsVerificationIfMemberExist(final FindPasswordRequest request) {
        guardianSmsService.sendVerificationSmsForPasswordReset(request);
    }

    @Transactional
    public TemporaryTokenResponse verifySmsCode(final SmsCodeRequest request) {
        return guardianSmsService.verifyCodeAndIssueToken(request);
    }

    @Transactional(readOnly = true)
    public boolean isEmailRegistered(EmailCheckRequest request) {
        return localLoginService.isEmailRegistered(request);
    }

    @Transactional
    public LocalSignupResponse localGuardianSignup(HttpServletRequest httpServletRequest,
                                                   final LocalGuardianSignupRequest request) {
        String tempToken = temporaryTokenService.extractFrom(httpServletRequest);
        TemporaryTokenDto dto = temporaryTokenService.parseAndValidate(tempToken);
        TemporaryMember temp = temporaryTokenService.loadTemporaryMemberOrThrow(dto.temporaryMemberId());

        LocalSignupResponse response = localLoginService.signupGuardianWithLocal(temp, request.email(), request.password());

        temporaryTokenService.deleteTemporaryMember(temp.getId());
        return response;
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
        return guardianTokenService.reissueTokenPair(request);
    }

    @Transactional
    public boolean changeMemberPassword(ChangePasswordRequest request, HttpServletRequest httpServletRequest) {
        return localLoginService.changePassword(request, httpServletRequest);
    }

    @Transactional
    public TokenPairResponse integrateSocialAccount(SocialIntegrationRequest request) {
        return socialLoginService.integrateSocialAccount(request);
    }

    @Transactional(readOnly = true)
    public CurrentMemberResponse getCurrentMemberInfo() {
        Member currentMember = memberUtil.getCurrentMember();

        String email = extractEmail(currentMember);
        List<String> providers = extractProviders(currentMember);
        boolean hasParents = hasParentProfiles(currentMember);

        return CurrentMemberResponse.of(
                currentMember.getName(),
                currentMember.getPhoneNumber(),
                email,
                providers,
                hasParents
        );
    }

    private String extractEmail(Member member) {
        if (member.getLocalAccount() != null) {
            return member.getLocalAccount().getEmail();
        }
        return member.getSocialAccounts().stream()
                .map(SocialAccount::getEmail)
                .filter(e -> e != null && !e.isBlank())
                .findFirst()
                .orElse(null);
    }

    private List<String> extractProviders(Member member) {
        List<String> providers = member.getSocialAccounts().stream()
                .map(SocialAccount::getProvider)
                .toList();

        if (member.getLocalAccount() != null) {
            providers = new ArrayList<>(providers);
            providers.add(PROVIDER_LOCAL);
        }

        return providers;
    }

    private boolean hasParentProfiles(Member member) {
        return member.getGuardianParentProfiles() != null &&
                !member.getGuardianParentProfiles().isEmpty();
    }

    @Transactional(readOnly = true)
    public void logout() {
        logoutService.logout();
    }

    @Transactional
    public void withdrawMember(MemberWithdrawRequest request) {
        memberWithdrawService.withdrawMember(request);
    }
}
