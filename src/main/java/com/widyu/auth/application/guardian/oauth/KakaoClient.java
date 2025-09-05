package com.widyu.auth.application.guardian.oauth;

import static com.widyu.global.constant.SecurityConstant.KAKAO_USER_ME_URL;
import static com.widyu.global.constant.SecurityConstant.TOKEN_PREFIX;

import com.widyu.auth.dto.response.KakaoAuthResponse;
import com.widyu.auth.dto.response.SocialClientResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component("KAKAO")
@RequiredArgsConstructor
public class KakaoClient implements OAuthClient {

    private final RestClient restClient;

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
