package com.widyu.auth.application.guardian.oauth;

import static com.widyu.global.constant.SecurityConstant.KAKAO_AUTH_URL;
import static com.widyu.global.constant.SecurityConstant.KAKAO_TOKEN_URL;
import static com.widyu.global.constant.SecurityConstant.KAKAO_USER_ME_URL;
import static com.widyu.global.constant.SecurityConstant.TOKEN_PREFIX;

import com.widyu.auth.domain.OAuthProvider;
import com.widyu.auth.dto.response.KakaoAuthResponse;
import com.widyu.auth.dto.response.OAuthTokenResponse;
import com.widyu.auth.dto.response.SocialClientResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.KakaoProperties;
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
@Component("KAKAO")
@RequiredArgsConstructor
public class KakaoClient implements OAuthClient {

    private final KakaoProperties kakaoProperties;
    private final RestClient restClient;
    private final OAuthStateService oAuthStateService;

    @Override
    public String getAuthCode(final OAuthProvider provider, HttpServletResponse response) throws IOException {
        String state = oAuthStateService.generateAndSaveState();

        String authorizationUrl =
                KAKAO_AUTH_URL
                        + "?response_type=code"
                        + "&client_id=" + kakaoProperties.clientId()
                        + "&redirect_uri=" + kakaoProperties.redirectUri()
                        + "&state=" + state;

        log.debug("카카오 OAuth 인가 URL: {}", authorizationUrl);
        return authorizationUrl;
    }

    @Override
    public OAuthTokenResponse getToken(final String authCode, final String state) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("code", authCode);
        params.add("client_id", kakaoProperties.clientId());
        params.add("redirect_uri", kakaoProperties.redirectUri());

        return restClient.post()
                .uri(KAKAO_TOKEN_URL)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .body(params)
                .exchange((req, res) -> {
                    if (!res.getStatusCode().is2xxSuccessful()) {
                        log.error("카카오 토큰 조회 실패, 상태 코드: {}", res.getStatusCode());
                        throw new BusinessException(ErrorCode.KAKAO_COMMUNICATION_ERROR);
                    }
                    return Objects.requireNonNull(res.bodyTo(OAuthTokenResponse.class));
                });
    }

    @Override
    public SocialClientResponse getUserInfo(final String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new BusinessException(ErrorCode.KAKAO_TOKEN_IS_BLANK);
        }

        KakaoAuthResponse kakao =
                restClient.get()
                        .uri(KAKAO_USER_ME_URL)
                        .header(HttpHeaders.AUTHORIZATION, TOKEN_PREFIX + accessToken)
                        .exchange((req, res) -> {
                            if (!res.getStatusCode().is2xxSuccessful()) {
                                log.error("카카오 사용자 조회 실패, 상태 코드: {}", res.getStatusCode());
                                throw new BusinessException(ErrorCode.KAKAO_COMMUNICATION_ERROR);
                            }
                            return Objects.requireNonNull(res.bodyTo(KakaoAuthResponse.class));
                        });

        return SocialClientResponse.of(
                kakao.id().toString(),
                kakao.kakaoAccount().email(),
                kakao.kakaoAccount().profile().nickname(),
                kakao.kakaoAccount().phoneNumber()
        );
    }
}
