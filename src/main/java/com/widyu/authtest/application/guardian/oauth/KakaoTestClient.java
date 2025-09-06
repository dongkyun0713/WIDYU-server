package com.widyu.authtest.application.guardian.oauth;

import static com.widyu.global.constant.SecurityConstant.KAKAO_AUTH_URL;
import static com.widyu.global.constant.SecurityConstant.KAKAO_TOKEN_URL;
import static com.widyu.global.constant.SecurityConstant.KAKAO_USER_ME_URL;
import static com.widyu.global.constant.SecurityConstant.TOKEN_PREFIX;

import com.widyu.authtest.domain.OAuthProvider;
import com.widyu.authtest.dto.response.KakaoAuthResponse;
import com.widyu.authtest.dto.response.OAuthTokenResponse;
import com.widyu.authtest.dto.response.SocialClientResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.KakaoProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Slf4j
@Component("KAKAO_TEST")
@RequiredArgsConstructor
public class KakaoTestClient implements OAuthTestClient {

    private final KakaoProperties kakaoProperties;
    private final RestClient restClient;
    private final OAuthStateTestService oAuthStateTestService;

    @Override
    public String getAuthCode(final OAuthProvider provider, HttpServletResponse response) throws IOException {
        String state = oAuthStateTestService.generateAndSaveState();

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
    public String generateAuthUrl(OAuthProvider provider) {
        String state = oAuthStateTestService.generateAndSaveState();
        
        return KAKAO_AUTH_URL
                + "?response_type=code"
                + "&client_id=" + kakaoProperties.clientId()
                + "&redirect_uri=" + kakaoProperties.redirectUri()
                + "&state=" + state;
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

        KakaoAuthResponse kakao = restClient.get()
                .uri(KAKAO_USER_ME_URL)
                .header(HttpHeaders.AUTHORIZATION, TOKEN_PREFIX + accessToken)
                .exchange((req, res) -> {
                    if (!res.getStatusCode().is2xxSuccessful()) {
                        log.error("카카오 사용자 조회 실패, 상태 코드: {}", res.getStatusCode());
                        throw new BusinessException(ErrorCode.KAKAO_COMMUNICATION_ERROR);
                    }
                    return Objects.requireNonNull(res.bodyTo(KakaoAuthResponse.class));
                });

        final String phoneNumber = normalizePhone(safe(() -> kakao.kakaoAccount().phoneNumber()));

        return SocialClientResponse.of(
                String.valueOf(kakao.id()),
                safe(() -> kakao.kakaoAccount().email()),
                safe(() -> kakao.kakaoAccount().profile().nickname()),
                phoneNumber
        );
    }

    /**
     * 카카오 전화번호 정규화: - 공백/하이픈/괄호 등 숫자·'+' 이외 문자 제거 - +82로 시작하면 국내형(0으로 치환)으로 변환 - 그 외는 국제번호 표기 유지(+포함) 예) "+82
     * 10-1234-5678" -> "01012345678" "+1-202-555-0123"  -> "+12025550123"
     */
    private String normalizePhone(String raw) {
        if (raw == null || raw.isBlank())
            return null;

        String cleaned = raw.replaceAll("[^0-9+]", "");
        if (cleaned.isBlank())
            return null;

        if (cleaned.indexOf('+') > 0) {
            cleaned = cleaned.charAt(0) == '+'
                    ? "+" + cleaned.substring(1).replace("+", "")
                    : cleaned.replace("+", "");
        }

        if (cleaned.startsWith("+82")) {
            return "0" + cleaned.substring(3);
        }

        return cleaned;
    }

    private static <T> T safe(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (NullPointerException e) {
            return null;
        }
    }
}
