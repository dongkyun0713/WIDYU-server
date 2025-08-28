package com.widyu.auth.api;

import com.widyu.auth.application.guardian.AuthService;
import com.widyu.auth.dto.request.EmailCheckRequest;
import com.widyu.auth.dto.request.LocalGuardianSignInRequest;
import com.widyu.auth.dto.request.LocalGuardianSignupRequest;
import com.widyu.auth.dto.request.SmsCodeRequest;
import com.widyu.auth.dto.request.SmsVerificationRequest;
import com.widyu.auth.dto.response.TemporaryTokenResponse;
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
@RequestMapping("/api/v1/auth")
public class GuardianAuthController implements AuthDocs {
    private final AuthService authService;

    @PostMapping("/sms/send")
    public ApiResponseTemplate<Void> sendSmsVerification(@Valid @RequestBody final SmsVerificationRequest request) {
        authService.sendSmsVerification(request);
        return ApiResponseTemplate.ok()
                .code("SMS_2001")
                .message("문자가 성공적으로 전송되었습니다.")
                .body(null);
    }

    @PostMapping("/sms/verify")
    public ApiResponseTemplate<TemporaryTokenResponse> verifySmsCode(@RequestBody @Valid final SmsCodeRequest request) {
        TemporaryTokenResponse response = authService.verifySmsCode(request);
        return ApiResponseTemplate.ok()
                .code("SMS_2002")
                .message("SMS 인증이 성공적으로 완료되었습니다.")
                .body(response);
    }

    @PostMapping("/signup/email/check")
    public ApiResponseTemplate<Boolean> isEmailRegistered(@RequestBody @Valid final EmailCheckRequest request) {
        boolean isRegistered = authService.isEmailRegistered(request);
        return ApiResponseTemplate.ok()
                .code("AUTH_2001")
                .message("이메일 등록 여부 확인 완료")
                .body(isRegistered);
    }

    @PostMapping("/signup/local/guardian")
    public ApiResponseTemplate<TokenPairResponse> localGuardianSignup(HttpServletRequest httpServletRequest,
                                                                      @RequestBody @Valid final LocalGuardianSignupRequest request) {
        return ApiResponseTemplate.ok()
                .code("AUTH_2002")
                .message("로컬 보호자 회원가입이 성공적으로 완료되었습니다.")
                .body(authService.localGuardianSignup(httpServletRequest, request));
    }

    @PostMapping("/sign-in/local/guardian")
    public ApiResponseTemplate<TokenPairResponse> localGuardianSignIn(
            @RequestBody @Valid final LocalGuardianSignInRequest request) {
        TokenPairResponse response = authService.localGuardianSignIn(request);

        return ApiResponseTemplate.ok()
                .code("AUTH_2003")
                .message("로컬 보호자 로그인 성공")
                .body(response);
    }

    @GetMapping("/sign-in/social/guardian")
    public void socialLogin(
            @RequestParam String provider,
            HttpServletResponse response
    ) throws IOException {
        authService.redirectToSocialLogin(provider, response);
    }

    @GetMapping("/callback/{provider}")
    public ApiResponseTemplate<TokenPairResponse> socialLoginCallback(
            @PathVariable String provider,
            @RequestParam String code,
            @RequestParam String state
    ) {
        return ApiResponseTemplate.ok()
                .code("AUTH_2004")
                .message("소셜 로그인 성공")
                .body(authService.processSocialLoginCallback(provider, code, state));
    }
}
