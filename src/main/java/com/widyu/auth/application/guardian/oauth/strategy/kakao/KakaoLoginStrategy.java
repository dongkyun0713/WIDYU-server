package com.widyu.auth.application.guardian.oauth.strategy.kakao;

import static com.widyu.global.constant.SecurityConstant.KAKAO_USER_ME_URL;
import static com.widyu.global.constant.SecurityConstant.TOKEN_PREFIX;

import com.widyu.auth.application.guardian.oauth.strategy.SocialLoginStrategy;
import com.widyu.auth.application.guardian.oauth.strategy.UserInfo;
import com.widyu.auth.domain.OAuthProvider;
import com.widyu.auth.dto.request.SocialLoginRequest;
import com.widyu.auth.dto.response.KakaoAuthResponse;
import com.widyu.auth.dto.response.SocialClientResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.PhoneNumberUtil;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoLoginStrategy implements SocialLoginStrategy {

    private final RestClient restClient;

    @Override
    public OAuthProvider getSupportedProvider() {
        return OAuthProvider.KAKAO;
    }

    @Override
    public void validateLoginRequest(SocialLoginRequest request) {
        if (request.accessToken() == null || request.accessToken().isBlank()) {
            log.error("카카오 액세스 토큰이 누락되었습니다");
            throw new BusinessException(ErrorCode.OAUTH_ACCESS_TOKEN_IS_BLANK);
        }
    }

    @Override
    public SocialClientResponse getUserInfo(SocialLoginRequest request) {
        try {
            KakaoAuthResponse kakao = restClient.get()
                    .uri(KAKAO_USER_ME_URL)
                    .header(HttpHeaders.AUTHORIZATION, TOKEN_PREFIX + request.accessToken())
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
        } catch (Exception e) {
            log.error("카카오 사용자 정보 조회 실패: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KAKAO_COMMUNICATION_ERROR);
        }
    }

    @Override
    public UserInfo processUserInfo(SocialClientResponse socialResponse, SocialLoginRequest request) {
        String normalizedPhone = PhoneNumberUtil.normalize(socialResponse.phoneNumber());

        return UserInfo.of(
                socialResponse.name(),
                socialResponse.email(),
                normalizedPhone
        );
    }

    @Override
    public void validateUserInfo(UserInfo userInfo) {
        validateEmail(userInfo);
        validateName(userInfo);
    }

    private void validateEmail(UserInfo userInfo) {
        if (!userInfo.hasEmail()) {
            log.error("카카오 이메일 정보가 누락되었습니다");
            throw new BusinessException(ErrorCode.SOCIAL_EMAIL_NOT_PROVIDED);
        }
    }

    private void validateName(UserInfo userInfo) {
        if (!userInfo.hasName()) {
            log.error("카카오 이름 정보가 누락되었습니다");
            throw new BusinessException(ErrorCode.SOCIAL_NAME_NOT_PROVIDED);
        }
    }

    private static <T> T safe(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (NullPointerException e) {
            return null;
        }
    }
}