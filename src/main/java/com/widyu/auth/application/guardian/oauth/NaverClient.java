package com.widyu.auth.application.guardian.oauth;

import static com.widyu.global.constant.SecurityConstant.NAVER_AUTH_URL;
import static com.widyu.global.constant.SecurityConstant.NAVER_TOKEN_URL;
import static com.widyu.global.constant.SecurityConstant.NAVER_USER_ME_URL;
import static com.widyu.global.constant.SecurityConstant.TOKEN_PREFIX;

import com.widyu.auth.domain.OAuthProvider;
import com.widyu.auth.dto.response.NaverAuthResponse;
import com.widyu.auth.dto.response.OAuthTokenResponse;
import com.widyu.auth.dto.response.SocialClientResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.NaverProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Slf4j
@Component("NAVER")
@RequiredArgsConstructor
public class NaverClient implements OAuthClient {

    private final NaverProperties naverProperties;
    private final RestClient restClient;
    private final OAuthStateService oAuthStateService;

    @Override
    public String getAuthCode(final OAuthProvider provider, HttpServletResponse response) throws IOException {
        String state = oAuthStateService.generateAndSaveState();

        // URL 구성 수정
        String redirectUri = NAVER_AUTH_URL +
                "?response_type=code" +
                "&client_id=" + naverProperties.clientId() +
                "&redirect_uri=" + naverProperties.redirectUri() +
                "&state=" + state;

        log.debug("네이버 OAuth 리다이렉트 URL: {}", redirectUri);
        return redirectUri;
    }

    @Override
    public OAuthTokenResponse getToken(final String authCode, final String state) {
        return restClient.post()
                .uri(NAVER_TOKEN_URL)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .body(createParams(authCode, state))
                .exchange((request, response) -> {
                            if (!response.getStatusCode().is2xxSuccessful()) {
                                log.error("네이버 토큰 조회 실패, 상태 코드: {}", response.getStatusCode());
                                throw new BusinessException(ErrorCode.NAVER_COMMUNICATION_ERROR);
                            }
                            return Objects.requireNonNull(response.bodyTo(OAuthTokenResponse.class));
                        }
                );
    }

    private MultiValueMap<String, String> createParams(final String authCode, final String state) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("code", authCode);
        params.add("client_id", naverProperties.clientId());
        params.add("client_secret", naverProperties.clientSecret());
        params.add("redirect_uri", naverProperties.redirectUri());
        params.add("state", state);
        return params;
    }

    @Override
    public SocialClientResponse getUserInfo(final String accessToken) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.NAVER_TOKEN_IS_BLANK);
        }

        NaverAuthResponse naverAuthResponse =
                restClient.get()
                        .uri(NAVER_USER_ME_URL)
                        .header("Authorization", TOKEN_PREFIX + accessToken)
                        .exchange((request, response) -> {
                            if (!response.getStatusCode().is2xxSuccessful()) {
                                throw new BusinessException(ErrorCode.NAVER_COMMUNICATION_ERROR);
                            }
                            return Objects.requireNonNull(
                                    response.bodyTo(NaverAuthResponse.class));
                        });

        return SocialClientResponse.of(
                naverAuthResponse.response().id(),
                naverAuthResponse.response().email(),
                naverAuthResponse.response().name(),
                naverAuthResponse.response().phoneNumber()
        );
    }
}
