package com.widyu.domain.auth.api;

import com.widyu.domain.auth.api.docs.UnifiedAuthDocs;
import com.widyu.domain.auth.application.guardian.GuardianAuthService;
import com.widyu.domain.auth.dto.request.RefreshTokenRequest;
import com.widyu.domain.auth.dto.response.TokenPairResponse;
import com.widyu.global.response.ApiResponseTemplate;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class UnifiedAuthController implements UnifiedAuthDocs {

    private final GuardianAuthService guardianAuthService;

    @PostMapping("/reissue")
    public ApiResponseTemplate<TokenPairResponse> reissueTokenPair(@Valid @RequestBody RefreshTokenRequest request) {
        TokenPairResponse response = guardianAuthService.reissueTokenPair(request);

        return ApiResponseTemplate.ok()
                .code("AUTH_2009")
                .message("토큰 재발급에 성공했습니다.")
                .body(response);
    }

    @PostMapping("/logout")
    public ApiResponseTemplate<Void> logout() {
        guardianAuthService.logout();

        return ApiResponseTemplate.ok()
                .code("AUTH_2010")
                .message("로그아웃에 성공했습니다.")
                .body(null);
    }
}
