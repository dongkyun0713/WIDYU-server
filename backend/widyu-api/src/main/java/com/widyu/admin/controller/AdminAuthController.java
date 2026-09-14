package com.widyu.admin.controller;

import com.widyu.admin.application.AdminAuthService;
import com.widyu.admin.controller.docs.AdminAuthDocs;
import com.widyu.admin.dto.response.AdminLoginResponse;
import com.widyu.auth.dto.response.TokenPairResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/admin")
public class AdminAuthController implements AdminAuthDocs {

    private static final String ADMIN_REFRESH_COOKIE = "admin_refresh_token";
    private static final int COOKIE_MAX_AGE = 7 * 24 * 60 * 60; // 7일

    private final AdminAuthService adminAuthService;

    @PostMapping("/login")
    public AdminLoginResponse login(@RequestBody Map<String, String> request,
                                    HttpServletResponse response) {
        TokenPairResponse tokens = adminAuthService.login(request.get("email"), request.get("password"));
        setRefreshTokenCookie(response, tokens.refreshToken());
        return AdminLoginResponse.of(tokens.memberId(), tokens.accessToken());
    }

    @PostMapping("/refresh")
    public AdminLoginResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = extractRefreshTokenCookie(request);
        TokenPairResponse tokens = adminAuthService.refresh(refreshToken);
        setRefreshTokenCookie(response, tokens.refreshToken());
        return AdminLoginResponse.of(tokens.memberId(), tokens.accessToken());
    }

    @PostMapping("/logout")
    public void logout(HttpServletResponse response) {
        clearRefreshTokenCookie(response);
    }

    private void setRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        ResponseCookie cookie = ResponseCookie.from(ADMIN_REFRESH_COOKIE, refreshToken)
                .httpOnly(true)
                .secure(true)
                .path("/api/v1/auth/admin/refresh")
                .maxAge(COOKIE_MAX_AGE)
                .sameSite("Strict")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(ADMIN_REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .path("/api/v1/auth/admin/refresh")
                .maxAge(0)
                .sameSite("Strict")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String extractRefreshTokenCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> ADMIN_REFRESH_COOKIE.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
