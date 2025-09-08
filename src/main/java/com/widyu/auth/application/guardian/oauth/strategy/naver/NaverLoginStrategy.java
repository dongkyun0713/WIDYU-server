package com.widyu.auth.application.guardian.oauth.strategy.naver;

import static com.widyu.global.constant.SecurityConstant.NAVER_USER_ME_URL;
import static com.widyu.global.constant.SecurityConstant.NAVER_WITHDRAW_URL;
import static com.widyu.global.constant.SecurityConstant.TOKEN_PREFIX;

import com.widyu.auth.application.guardian.oauth.strategy.SocialLoginStrategy;
import com.widyu.auth.application.guardian.oauth.strategy.UserInfo;
import com.widyu.auth.domain.OAuthProvider;
import com.widyu.auth.dto.request.SocialLoginRequest;
import com.widyu.auth.dto.response.NaverAuthResponse;
import com.widyu.auth.dto.response.SocialClientResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.PhoneNumberUtil;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class NaverLoginStrategy implements SocialLoginStrategy {

    private final RestClient restClient;

    @Override
    public OAuthProvider getSupportedProvider() {
        return OAuthProvider.NAVER;
    }

    @Override
    public void validateLoginRequest(SocialLoginRequest request) {
        if (request.accessToken() == null || request.accessToken().isBlank()) {
            log.error("네이버 액세스 토큰이 누락되었습니다");
            throw new BusinessException(ErrorCode.OAUTH_ACCESS_TOKEN_IS_BLANK);
        }
    }

    @Override
    public SocialClientResponse getUserInfo(SocialLoginRequest request) {
        try {
            NaverAuthResponse naverAuthResponse = restClient.get()
                    .uri(NAVER_USER_ME_URL)
                    .header("Authorization", TOKEN_PREFIX + request.accessToken())
                    .exchange((req, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            log.error("네이버 사용자 조회 실패, 상태 코드: {}", response.getStatusCode());
                            throw new BusinessException(ErrorCode.NAVER_COMMUNICATION_ERROR);
                        }
                        return Objects.requireNonNull(response.bodyTo(NaverAuthResponse.class));
                    });

            String phoneNumber = PhoneNumberUtil.normalize(naverAuthResponse.response().phoneNumber());

            return SocialClientResponse.of(
                    naverAuthResponse.response().id(),
                    naverAuthResponse.response().email(),
                    naverAuthResponse.response().name(),
                    phoneNumber
            );
        } catch (Exception e) {
            log.error("네이버 사용자 정보 조회 실패: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.NAVER_COMMUNICATION_ERROR);
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
            log.error("네이버 이메일 정보가 누락되었습니다");
            throw new BusinessException(ErrorCode.SOCIAL_EMAIL_NOT_PROVIDED);
        }
    }

    private void validateName(UserInfo userInfo) {
        if (!userInfo.hasName()) {
            log.error("네이버 이름 정보가 누락되었습니다");
            throw new BusinessException(ErrorCode.SOCIAL_NAME_NOT_PROVIDED);
        }
    }

    @Override
    public void withdrawSocialAccount(String accessToken, String oauthId) {
        try {
            log.info("네이버 계정 탈퇴 요청 시작: oauthId={}", oauthId);
            
            restClient.delete()
                    .uri(uriBuilder -> uriBuilder
                            .path(NAVER_WITHDRAW_URL)
                            .queryParam("grant_type", "delete")
                            .queryParam("access_token", accessToken)
                            .build())
                    .exchange((req, res) -> {
                        if (!res.getStatusCode().is2xxSuccessful()) {
                            log.error("네이버 계정 탈퇴 실패, 상태 코드: {}", res.getStatusCode());
                            throw new BusinessException(ErrorCode.NAVER_WITHDRAW_ERROR);
                        }
                        log.info("네이버 계정 탈퇴 성공: oauthId={}", oauthId);
                        return null;
                    });
        } catch (Exception e) {
            log.error("네이버 계정 탈퇴 중 오류 발생: oauthId={}, error={}", oauthId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.NAVER_WITHDRAW_ERROR);
        }
    }
}
