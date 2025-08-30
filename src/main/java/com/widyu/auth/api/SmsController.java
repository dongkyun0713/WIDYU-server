package com.widyu.auth.api;

import com.widyu.auth.application.guardian.AuthService;
import com.widyu.auth.dto.request.FindPasswordRequest;
import com.widyu.auth.dto.request.SmsCodeRequest;
import com.widyu.auth.dto.request.SmsVerificationRequest;
import com.widyu.auth.dto.response.TemporaryTokenResponse;
import com.widyu.global.response.ApiResponseTemplate;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/sms")
public class SmsController implements SmsDocs {

    private final AuthService authService;

    @PostMapping("/send")
    public ApiResponseTemplate<Void> sendSmsVerification(@Valid @RequestBody SmsVerificationRequest request) {
        authService.sendSmsVerification(request);
        return ApiResponseTemplate.ok()
                .code("SMS_2001")
                .message("문자가 성공적으로 전송되었습니다.")
                .body(null);
    }

    @PostMapping("/verify")
    public ApiResponseTemplate<TemporaryTokenResponse> verifySmsCode(@Valid @RequestBody SmsCodeRequest request) {
        TemporaryTokenResponse res = authService.verifySmsCode(request);
        return ApiResponseTemplate.ok()
                .code("SMS_2002")
                .message("SMS 인증이 성공적으로 완료되었습니다.")
                .body(res);
    }

    @PostMapping("/send-if-member-exist")
    public ApiResponseTemplate<Void> sendSmsVerificationIfMemberExist(@Valid @RequestBody FindPasswordRequest request) {
        authService.sendSmsVerificationIfMemberExist(request);
        return ApiResponseTemplate.ok()
                .code("SMS_2003")
                .message("문자가 성공적으로 전송되었습니다.")
                .body(null);
    }
}
