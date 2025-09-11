package com.widyu.domain.auth.api;

import com.widyu.domain.auth.api.docs.GuardianAuthDocs;
import com.widyu.domain.auth.application.guardian.GuardianAuthService;
import com.widyu.domain.auth.dto.request.AppleSignUpRequest;
import com.widyu.domain.auth.dto.request.ChangePasswordRequest;
import com.widyu.domain.auth.dto.request.EmailCheckRequest;
import com.widyu.domain.auth.dto.request.LocalGuardianSignInRequest;
import com.widyu.domain.auth.dto.request.LocalGuardianSignupRequest;
import com.widyu.domain.auth.dto.request.MemberWithdrawRequest;
import com.widyu.domain.auth.dto.request.SmsVerificationRequest;
import com.widyu.domain.auth.dto.request.SocialIntegrationRequest;
import com.widyu.domain.auth.dto.request.SocialLoginRequest;
import com.widyu.domain.auth.dto.response.CurrentMemberResponse;
import com.widyu.domain.auth.dto.response.LocalSignupResponse;
import com.widyu.domain.auth.dto.response.MemberInfoResponse;
import com.widyu.domain.auth.dto.response.SocialLoginResponse;
import com.widyu.domain.auth.dto.response.TokenPairResponse;
import com.widyu.domain.auth.dto.response.UserProfile;
import com.widyu.global.response.ApiResponseTemplate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/guardians")
public class GuardianAuthController implements GuardianAuthDocs {
    private final GuardianAuthService guardianAuthService;

    @PostMapping("/email/check")
    public ApiResponseTemplate<Boolean> isEmailRegistered(@RequestBody @Valid final EmailCheckRequest request) {
        boolean isRegistered = guardianAuthService.isEmailRegistered(request);
        return ApiResponseTemplate.ok()
                .code("AUTH_2001")
                .message("이메일 등록 여부 확인 완료")
                .body(isRegistered);
    }

    @PostMapping("/sign-up/local")
    public ApiResponseTemplate<LocalSignupResponse> signupLocal(
            HttpServletRequest httpServletRequest,
            @RequestBody @Valid final LocalGuardianSignupRequest request
    ) {
        return ApiResponseTemplate.ok()
                .code("AUTH_2002")
                .message("로컬 보호자 회원가입이 성공적으로 완료되었습니다.")
                .body(guardianAuthService.localGuardianSignup(httpServletRequest, request));
    }

    @PostMapping("/sign-in/local")
    public ApiResponseTemplate<TokenPairResponse> signInLocal(
            @RequestBody @Valid final LocalGuardianSignInRequest request
    ) {
        TokenPairResponse response = guardianAuthService.localGuardianSignIn(request);
        return ApiResponseTemplate.ok()
                .code("AUTH_2003")
                .message("로컬 보호자 로그인 성공")
                .body(response);
    }

    @PostMapping("/sign-in/social")
    public ApiResponseTemplate<SocialLoginResponse> signInSocial(
            @RequestParam String provider,
            @RequestBody @Valid final SocialLoginRequest request
    ) {
            SocialLoginResponse loginResponse = guardianAuthService.socialLogin(provider, request);
        return ApiResponseTemplate.ok()
                .code("AUTH_2004")
                .message("소셜 로그인 성공")
                .body(loginResponse);

    }

    @PostMapping("/email")
    public ApiResponseTemplate<MemberInfoResponse> findMemberByPhoneNumber(
            @RequestBody SmsVerificationRequest request
    ) {
        MemberInfoResponse response = guardianAuthService.findMemberByPhoneNumberAndName(request);

        return ApiResponseTemplate.ok()
                .code("AUTH_2006")
                .message("휴대폰 번호로 회원 조회 성공")
                .body(response);
    }

    @PatchMapping("/password")
    public ApiResponseTemplate<Boolean> changePassword(
            @RequestBody @Valid final ChangePasswordRequest request,
            HttpServletRequest httpServletRequest
    ) {
        boolean result = guardianAuthService.changeMemberPassword(request, httpServletRequest);
        return ApiResponseTemplate.ok()
                .code("AUTH_2007")
                .message("비밀번호 변경 성공")
                .body(result);
    }

    @PatchMapping("/apple/phone-number")
    public ApiResponseTemplate<Void> updatePhoneNumberIfAppleSignUp(
            @RequestBody @Valid final AppleSignUpRequest request,
            HttpServletRequest httpServletRequest
    ) {
        guardianAuthService.updatePhoneNumberIfAppleSignUp(request, httpServletRequest);
        return ApiResponseTemplate.ok()
                .code("AUTH_2008")
                .message("애플 로그인 회원 전화번호 업데이트 성공")
                .body(null);
    }

    @GetMapping("/profile/temporary")
    public ApiResponseTemplate<UserProfile> getUserProfileByTemporaryToken(
            HttpServletRequest httpServletRequest
    ) {
        UserProfile userProfile = guardianAuthService.getUserProfileByTemporaryToken(httpServletRequest);
        return ApiResponseTemplate.ok()
                .code("AUTH_2009")
                .message("임시 토큰으로 사용자 프로필 조회 성공")
                .body(userProfile);
    }

    @PostMapping("/social/integration")
    public ApiResponseTemplate<TokenPairResponse> integrateSocialAccount(
            @RequestBody @Valid final SocialIntegrationRequest request
    ) {
        TokenPairResponse tokenPair = guardianAuthService.integrateSocialAccount(request);
        return ApiResponseTemplate.ok()
                .code("AUTH_2010")
                .message("소셜 계정 연동 성공")
                .body(tokenPair);
    }

    @GetMapping("/me")
    public ApiResponseTemplate<CurrentMemberResponse> getCurrentMemberInfo() {
        CurrentMemberResponse memberInfo = guardianAuthService.getCurrentMemberInfo();
        return ApiResponseTemplate.ok()
                .code("AUTH_2011")
                .message("현재 회원 정보 조회 성공")
                .body(memberInfo);
    }

    @DeleteMapping("/withdraw")
    public ApiResponseTemplate<Void> withdrawMember(
            @RequestBody @Valid final MemberWithdrawRequest request
    ) {
        guardianAuthService.withdrawMember(request);
        return ApiResponseTemplate.ok()
                .code("AUTH_2012")
                .message("회원 탈퇴가 성공적으로 완료되었습니다")
                .body(null);
    }
}
