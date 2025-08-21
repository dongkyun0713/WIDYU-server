package com.widyu.auth.api;

import com.widyu.auth.application.AuthService;
import com.widyu.auth.dto.request.LocalGuardianSignupRequest;
import com.widyu.auth.dto.request.SmsCodeRequest;
import com.widyu.auth.dto.request.SmsVerificationRequest;
import com.widyu.auth.dto.response.TemporaryTokenResponse;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.global.response.ApiResponseTemplate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController implements AuthDocs {
    private final AuthService authService;

    @PostMapping("/sms/send")
    public ApiResponseTemplate<Void> sendSmsVerification(@Valid @RequestBody final SmsVerificationRequest request) {
        authService.sendSmsVerification(request);
        return ApiResponseTemplate.ok()
                .code("AUTH_2001")
                .message("문자가 성공적으로 전송되었습니다.")
                .body(null);
    }

    @PostMapping("/sms/verify")
    public ApiResponseTemplate<TemporaryTokenResponse> verifySmsCode(@RequestBody @Valid final SmsCodeRequest request) {
        TemporaryTokenResponse response = authService.verifySmsCode(request);
        return ApiResponseTemplate.ok()
                .code("AUTH_2002")
                .message("SMS 인증이 성공적으로 완료되었습니다.")
                .body(response);
    }

    @PostMapping("/local-guardian/signup")
    public ApiResponseTemplate<TokenPairResponse> localGuardianSignup(HttpServletRequest httpServletRequest,
                                                                      @RequestBody @Valid final LocalGuardianSignupRequest request) {
        return ApiResponseTemplate.ok()
                .code("AUTH_2003")
                .message("로컬 가디언 회원가입이 성공적으로 완료되었습니다.")
                .body(authService.localGuardianSignup(httpServletRequest, request));
    }

}
