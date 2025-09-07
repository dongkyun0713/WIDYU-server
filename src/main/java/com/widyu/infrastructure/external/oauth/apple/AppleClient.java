package com.widyu.infrastructure.external.oauth.apple;

import static com.widyu.global.constant.SecurityConstant.APPLE_TOKEN_URL;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.auth.dto.request.AppleTokenRequest;
import com.widyu.auth.dto.response.AppleIdTokenPayload;
import com.widyu.auth.dto.response.AppleTokenResponse;
import com.widyu.auth.dto.response.SocialClientResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.AppleProperties;
import com.widyu.infrastructure.external.oauth.naver.OAuthClient;
import java.util.Base64;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component("APPLE")
@RequiredArgsConstructor
public class AppleClient implements OAuthClient {

    private final AppleProperties appleProperties;
    private final AppleJwtUtils appleJwtUtils;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Override
    public SocialClientResponse getUserInfo(final String authorizationCode) {
        if (authorizationCode == null || authorizationCode.isBlank()) {
            throw new BusinessException(ErrorCode.APPLE_AUTHORIZATION_CODE_IS_BLANK);
        }

        try {
            String clientSecret = appleJwtUtils.generateClientSecret();

            AppleTokenResponse tokenResponse = exchangeCodeForTokens(authorizationCode, clientSecret);

            AppleIdTokenPayload idTokenPayload = parseIdToken(tokenResponse.idToken());

            return SocialClientResponse.of(
                    idTokenPayload.subject(),
                    idTokenPayload.email(),
                    null,
                    null
            );
        } catch (Exception e) {
            log.error("Apple authentication failed: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.APPLE_COMMUNICATION_ERROR);
        }
    }

    private AppleTokenResponse exchangeCodeForTokens(String authorizationCode, String clientSecret) {
        AppleTokenRequest tokenRequest = AppleTokenRequest.of(
                appleProperties.clientId(),
                clientSecret,
                authorizationCode,
                appleProperties.redirectUri()
        );

        return restClient.post()
                .uri(APPLE_TOKEN_URL)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .body(convertToFormData(tokenRequest))
                .exchange((req, res) -> {
                    if (!res.getStatusCode().is2xxSuccessful()) {
                        log.error("Apple token exchange failed, status: {}", res.getStatusCode());
                        throw new BusinessException(ErrorCode.APPLE_COMMUNICATION_ERROR);
                    }
                    return Objects.requireNonNull(res.bodyTo(AppleTokenResponse.class));
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
            log.error("Failed to parse Apple ID token: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.APPLE_COMMUNICATION_ERROR);
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
