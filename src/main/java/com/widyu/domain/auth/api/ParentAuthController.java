package com.widyu.domain.auth.api;

import com.widyu.domain.auth.api.docs.ParentAuthDocs;
import com.widyu.domain.auth.application.parent.ParentAuthService;
import com.widyu.domain.auth.dto.request.ParentSignInRequest;
import com.widyu.domain.auth.dto.request.ParentSignUpRequest;
import com.widyu.domain.auth.dto.response.TokenPairResponse;
import com.widyu.global.response.ApiResponseTemplate;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/parents")
public class ParentAuthController implements ParentAuthDocs {
    private final ParentAuthService parentAuthService;

    @PostMapping("/sign-up")
    public ApiResponseTemplate<Void> signUp(@Valid @RequestBody List<ParentSignUpRequest> request) {
        parentAuthService.parentSignUpBulk(request);

        return ApiResponseTemplate.ok()
                .code("AUTH_2007")
                .message("로컬 부모님 회원가입이 성공적으로 완료되었습니다.")
                .body(null);
    }

    @PostMapping("/sign-in")
    public ApiResponseTemplate<TokenPairResponse> signIn(@Valid @RequestBody ParentSignInRequest request) {
        TokenPairResponse response = parentAuthService.parentSignIn(request);

        return ApiResponseTemplate.ok()
                .code("AUTH_2008")
                .message("로컬 부모님 로그인 성공")
                .body(response);
    }
}
