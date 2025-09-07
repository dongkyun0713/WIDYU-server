package com.widyu.infrastructure.external.oauth.kakao;

import static com.widyu.global.constant.SecurityConstant.KAKAO_USER_ME_URL;
import static com.widyu.global.constant.SecurityConstant.TOKEN_PREFIX;

import com.widyu.auth.dto.response.KakaoAuthResponse;
import com.widyu.auth.dto.response.SocialClientResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.PhoneNumberUtil;
import com.widyu.infrastructure.external.oauth.naver.OAuthClient;
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

        String phoneNumber = PhoneNumberUtil.normalize(kakao.kakaoAccount().phoneNumber());

        return SocialClientResponse.of(
                String.valueOf(kakao.id()),
                safe(() -> kakao.kakaoAccount().email()),
                safe(() -> kakao.kakaoAccount().name()),
                phoneNumber
        );
    }


    private static <T> T safe(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (NullPointerException e) {
            return null;
        }
    }
}
