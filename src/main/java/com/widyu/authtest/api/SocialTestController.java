package com.widyu.authtest.api;

import com.widyu.authtest.api.docs.SocialTestAuthDocs;
import com.widyu.authtest.application.guardian.AuthTestService;
import com.widyu.authtest.dto.response.SocialLoginResponse;
import com.widyu.global.response.ApiResponseTemplate;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/test")
public class SocialTestController implements SocialTestAuthDocs {
    private final AuthTestService authTestService;

    @GetMapping("/sign-in/social")
    public ApiResponseTemplate<String> signInSocial(
            @RequestParam String provider,
            HttpServletResponse response
    ) throws IOException {
        return ApiResponseTemplate.ok()
                .code("AUTH_2005")
                .message("소셜 로그인 페이지로 리다이렉트")
                .body(authTestService.redirectToSocialLogin(provider, response));
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
                .body(authTestService.processSocialLoginCallback(provider, code, state));
    }
}
