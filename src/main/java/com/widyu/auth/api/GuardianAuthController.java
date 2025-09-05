package com.widyu.auth.api;

import com.widyu.auth.api.docs.GuardianAuthDocs;
import com.widyu.auth.application.guardian.AuthService;
import com.widyu.auth.dto.request.ChangePasswordRequest;
import com.widyu.auth.dto.request.EmailCheckRequest;
import com.widyu.auth.dto.request.LocalGuardianSignInRequest;
import com.widyu.auth.dto.request.LocalGuardianSignupRequest;
import com.widyu.auth.dto.request.SmsVerificationRequest;
import com.widyu.auth.dto.request.SocialLoginRequest;
import com.widyu.auth.dto.response.MemberInfoResponse;
import com.widyu.auth.dto.response.SocialLoginResponse;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.global.response.ApiResponseTemplate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final AuthService authService;

    @PostMapping("/email/check")
    public ApiResponseTemplate<Boolean> isEmailRegistered(@RequestBody @Valid final EmailCheckRequest request) {
        boolean isRegistered = authService.isEmailRegistered(request);
        return ApiResponseTemplate.ok()
                .code("AUTH_2001")
                .message("이메일 등록 여부 확인 완료")
                .body(isRegistered);
    }

    @PostMapping("/sign-up/local")
    public ApiResponseTemplate<TokenPairResponse> signupLocal(
            HttpServletRequest httpServletRequest,
            @RequestBody @Valid final LocalGuardianSignupRequest request
    ) {
        return ApiResponseTemplate.ok()
                .code("AUTH_2002")
                .message("로컬 보호자 회원가입이 성공적으로 완료되었습니다.")
                .body(authService.localGuardianSignup(httpServletRequest, request));
    }

    @PostMapping("/sign-in/local")
    public ApiResponseTemplate<TokenPairResponse> signInLocal(
            @RequestBody @Valid final LocalGuardianSignInRequest request
    ) {
        TokenPairResponse response = authService.localGuardianSignIn(request);
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
        return ApiResponseTemplate.ok()
                .code("AUTH_2004")
                .message("소셜 로그인 성공")
                .body(authService.socialLogin(provider, request));
    }

    @PostMapping("/email")
    public ApiResponseTemplate<MemberInfoResponse> findMemberByPhoneNumber(
            @RequestBody SmsVerificationRequest request
    ) {
        MemberInfoResponse response = authService.findMemberByPhoneNumberAndName(request);

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
        boolean result = authService.changeMemberPassword(request, httpServletRequest);
        return ApiResponseTemplate.ok()
                .code("AUTH_2007")
                .message("비밀번호 변경 성공")
                .body(result);
    }
}
