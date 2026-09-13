package com.widyu.auth.application.guardian.oauth.strategy.apple;

import static com.widyu.global.constant.SecurityConstant.APPLE_TOKEN_URL;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.auth.application.guardian.oauth.strategy.SocialLoginStrategy;
import com.widyu.auth.application.guardian.oauth.strategy.UserInfo;
import com.widyu.auth.OAuthProvider;
import com.widyu.auth.dto.request.AppleTokenRequest;
import com.widyu.auth.dto.request.SocialLoginRequest;
import com.widyu.auth.dto.response.AppleIdTokenPayload;
import com.widyu.auth.dto.response.AppleTokenResponse;
import com.widyu.auth.dto.response.SocialClientResponse;
import com.widyu.global.constant.Platform;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.AppleProperties;
import com.widyu.global.util.PhoneNumberUtil;
import java.util.Base64;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppleLoginStrategy implements SocialLoginStrategy {

    private final AppleProperties appleProperties;
    private final AppleJwtUtils appleJwtUtils;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Override
    public OAuthProvider getSupportedProvider() {
        return OAuthProvider.APPLE;
    }

    @Override
    public void validateLoginRequest(SocialLoginRequest request) {
        if (request.authorizationCode() == null || request.authorizationCode().isBlank()) {
            log.error("애플 인증 코드가 누락되었습니다");
            throw new BusinessException(ErrorCode.APPLE_AUTHORIZATION_CODE_IS_BLANK);
        }
    }

    @Override
    public SocialClientResponse getUserInfo(SocialLoginRequest request) {
        try {
            String clientSecret = appleJwtUtils.generateClientSecret(request.platform());
            AppleTokenResponse tokenResponse = exchangeCodeForTokens(request.authorizationCode(), clientSecret, request.platform());
            AppleIdTokenPayload idTokenPayload = parseIdToken(tokenResponse.idToken());

            // 클라이언트에서 email 보내주면 사용, 없으면 ID Token에서 조회
            String email = idTokenPayload.email();
            if (request.profile() != null && request.profile().email() != null) {
                email = request.profile().email();
            }

            // 클라이언트에서 name 보내주면 사용
            String name = null;
            if (request.profile() != null) {
                name = request.profile().name();
            }

            return SocialClientResponse.of(
                    idTokenPayload.subject(),
                    email,
                    name,
                    null,
                    tokenResponse.refreshToken()
            );
        } catch (Exception e) {
            log.error("애플 사용자 정보 조회 실패: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.APPLE_COMMUNICATION_ERROR);
        }
    }

    @Override
    public UserInfo processUserInfo(SocialClientResponse socialResponse, SocialLoginRequest request) {
        String name = socialResponse.name();
        String email = socialResponse.email();
        String phoneNumber = socialResponse.phoneNumber();

        if (request.profile() != null) {
            name = getValueOrDefault(name, request.profile().name());
            email = getValueOrDefault(email, request.profile().email());
        }

        // name이 null이거나 빈 값이면 "익명의 사용자"로 설정
        if (name == null || name.isBlank()) {
            name = "익명의 사용자";
            log.info("애플 로그인: 이름 정보 없음, '익명의 사용자'로 설정");
        }

        String normalizedPhone = PhoneNumberUtil.normalize(phoneNumber);
        return UserInfo.of(name, email, normalizedPhone);
    }

    @Override
    public void validateUserInfo(UserInfo userInfo) {
        validateEmail(userInfo);
    }

    private void validateEmail(UserInfo userInfo) {
        if (!userInfo.hasEmail()) {
            log.error("애플 이메일 정보가 누락되었습니다");
            throw new BusinessException(ErrorCode.SOCIAL_EMAIL_NOT_PROVIDED);
        }
    }

    private String getValueOrDefault(String currentValue, String defaultValue) {
        if (currentValue != null && !currentValue.isBlank()) {
            return currentValue;
        }
        return defaultValue;
    }



    private String getClientIdByPlatform(String platformValue) {
        Platform platform = Platform.from(platformValue);
        return switch (platform) {
            case ANDROID -> appleProperties.androidClientId();
            case IOS -> appleProperties.iosClientId();
        };
    }

    private AppleTokenResponse exchangeCodeForTokens(String authorizationCode, String clientSecret, String platformValue) {
        String clientId = getClientIdByPlatform(platformValue);
        log.info("애플 토큰 교환 시작: platform={}", platformValue);
        
        AppleTokenRequest tokenRequest = AppleTokenRequest.of(
                clientId,
                clientSecret,
                authorizationCode,
                appleProperties.redirectUri()
        );

        String formData = convertToFormData(tokenRequest);
        log.debug("애플 토큰 요청 데이터: {}", formData);

        return restClient.post()
                .uri(APPLE_TOKEN_URL)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .body(formData)
                .exchange((req, res) -> {
                    log.info("애플 토큰 응답: 상태코드={}, 헤더={}",
                            res.getStatusCode(), res.getHeaders());
                    
                    if (!res.getStatusCode().is2xxSuccessful()) {
                        String responseBody = "";
                        try {
                            responseBody = new String(res.getBody().readAllBytes());
                            log.error("애플 토큰 교환 실패 - platform: {}, 상태코드: {}, 응답본문: {}", 
                                    platformValue, res.getStatusCode(), responseBody);
                        } catch (Exception e) {
                            log.error("애플 토큰 교환 실패 - platform: {}, 상태코드: {}, 응답본문 읽기 실패: {}", 
                                    platformValue, res.getStatusCode(), e.getMessage());
                        }
                        throw new BusinessException(ErrorCode.APPLE_COMMUNICATION_ERROR);
                    }
                    
                    try {
                        AppleTokenResponse response = Objects.requireNonNull(res.bodyTo(AppleTokenResponse.class));
                        log.info("애플 토큰 교환 성공: platform={}", platformValue);
                        return response;
                    } catch (Exception e) {
                        log.error("애플 토큰 응답 파싱 실패: {}", e.getMessage(), e);
                        throw new BusinessException(ErrorCode.APPLE_TOKEN_RESPONSE_INVALID);
                    }
                });
    }

    private AppleIdTokenPayload parseIdToken(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length != 3) {
                throw new BusinessException(ErrorCode.APPLE_COMMUNICATION_ERROR);
            }

            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            return objectMapper.readValue(payload, AppleIdTokenPayload.class);
        } catch (JsonProcessingException e) {
            log.error("애플 ID 토큰 파싱 실패: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.APPLE_COMMUNICATION_ERROR);
        }
    }

    @Override
    public void withdrawSocialAccount(String refreshToken, String oauthId) {
        try {
            log.info("애플 계정 탈퇴 요청 시작 (리프레시 토큰 사용)");

            if (refreshToken == null || refreshToken.isBlank()) {
                log.warn("애플 계정 탈퇴를 위한 리프레시 토큰이 없습니다");
                throw new BusinessException(ErrorCode.APPLE_WITHDRAW_ERROR);
            }

            String clientSecret = appleJwtUtils.generateClientSecret();
            String formData = String.format(
                    "client_id=%s&client_secret=%s&token=%s&token_type_hint=refresh_token",
                    appleProperties.iosClientId(),
                    clientSecret,
                    refreshToken
            );

            restClient.post()
                    .uri(APPLE_TOKEN_URL.replace("/token", "/revoke"))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                    .body(formData)
                    .exchange((req, res) -> {
                        if (!res.getStatusCode().is2xxSuccessful()) {
                            log.error("애플 계정 탈퇴 실패, 상태 코드: {}", res.getStatusCode());
                            throw new BusinessException(ErrorCode.APPLE_WITHDRAW_ERROR);
                        }
                        log.info("애플 계정 탈퇴 성공 (리프레시 토큰 사용)");
                        return null;
                    });
        } catch (Exception e) {
            log.error("애플 계정 탈퇴 중 오류 발생: errorType={}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.APPLE_WITHDRAW_ERROR);
        }
    }

    private String convertToFormData(AppleTokenRequest request) {
        return String.format(
                "client_id=%s&client_secret=%s&code=%s&grant_type=%s&redirect_uri=%s",
                request.clientId(),
                request.clientSecret(),
                request.code(),
                request.grantType(),
                request.redirectUri()
        );
    }
}
