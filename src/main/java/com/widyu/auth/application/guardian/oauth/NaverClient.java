package com.widyu.auth.application.guardian.oauth;

import static com.widyu.global.constant.SecurityConstant.NAVER_USER_ME_URL;
import static com.widyu.global.constant.SecurityConstant.TOKEN_PREFIX;

import com.widyu.auth.dto.response.NaverAuthResponse;
import com.widyu.auth.dto.response.SocialClientResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component("NAVER")
@RequiredArgsConstructor
public class NaverClient implements OAuthClient {

    private final RestClient restClient;

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

        String phoneNumber = naverAuthResponse.response().phoneNumber();
        if (phoneNumber != null) {
            phoneNumber = phoneNumber.replaceAll("-", "");
        }

        return SocialClientResponse.of(
                naverAuthResponse.response().id(),
                naverAuthResponse.response().email(),
                naverAuthResponse.response().name(),
                phoneNumber
        );
    }
}
