package com.widyu.auth.api;

import com.widyu.auth.application.guardian.AuthService;
import com.widyu.auth.dto.request.EmailCheckRequest;
import com.widyu.auth.dto.request.LocalGuardianSignInRequest;
import com.widyu.auth.dto.request.LocalGuardianSignupRequest;
import com.widyu.auth.dto.request.SmsVerificationRequest;
import com.widyu.auth.dto.response.MemberInfoResponse;
import com.widyu.auth.dto.response.SocialLoginResponse;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.global.response.ApiResponseTemplate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @GetMapping("/sign-in/social")
    public ApiResponseTemplate<String> signInSocial(
            @RequestParam String provider,
            HttpServletResponse response
    ) throws IOException {
        return ApiResponseTemplate.ok()
                .code("AUTH_2005")
                .message("소셜 로그인 페이지로 리다이렉트")
                .body(authService.redirectToSocialLogin(provider, response));
    }

    @GetMapping("/callback/{provider}")
    public ApiResponseTemplate<SocialLoginResponse> socialLoginCallback(
            @PathVariable String provider,
            @RequestParam String code,
            @RequestParam String state
    ) {
        return ApiResponseTemplate.ok()
                .code("AUTH_2004")
                .message("소셜 로그인 성공")
                .body(authService.processSocialLoginCallback(provider, code, state));
    }

    @GetMapping("/member/email")
    public ApiResponseTemplate<MemberInfoResponse> findMemberByPhoneNumber(
            @RequestBody SmsVerificationRequest request
            ) {
        MemberInfoResponse response = authService.findMemberByPhoneNumber(request);

        return ApiResponseTemplate.ok()
                .code("AUTH_2006")
                .message("휴대폰 번호로 회원 조회 성공")
                .body(response);
    }
}
