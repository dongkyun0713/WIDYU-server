package com.widyu.auth.api;

import com.widyu.auth.application.parent.ParentLoginService;
import com.widyu.auth.dto.request.ParentSignInRequest;
import com.widyu.auth.dto.request.ParentSignUpRequest;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.global.response.ApiResponseTemplate;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/parents")
public class ParentAuthController implements ParentAuthDocs {
    private final ParentLoginService parentLoginService;

    @PostMapping("/sign-up")
    public ApiResponseTemplate<Void> signUp(@Valid @RequestBody ParentSignUpRequest request) {
        parentLoginService.parentSignUp(request);

        return ApiResponseTemplate.ok()
                .code("AUTH_2005")
                .message("로컬 학부모 회원가입이 성공적으로 완료되었습니다.")
                .body(null);
    }

    @PostMapping("/sign-in")
    public ApiResponseTemplate<TokenPairResponse> signIn(@Valid @RequestBody ParentSignInRequest request) {
        TokenPairResponse response = parentLoginService.parentSignIn(request);

        return ApiResponseTemplate.ok()
                .code("AUTH_2006")
                .message("로컬 학부모 로그인 성공")
                .body(response);
    }
}
