package com.widyu.auth.application.guardian.oauth.strategy.naver;

import static com.widyu.global.constant.SecurityConstant.NAVER_WITHDRAW_URL;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.widyu.auth.application.guardian.oauth.strategy.UserInfo;
import com.widyu.auth.dto.request.SocialLoginRequest;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.NaverProperties;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("NaverLoginStrategy 단위 테스트")
class NaverLoginStrategyTest {

    private static final NaverProperties NAVER_PROPERTIES =
            new NaverProperties("naver-client-id", "naver-client-secret", "redirect-uri");

    private final NaverLoginStrategy strategy = new NaverLoginStrategy(RestClient.builder().build(), NAVER_PROPERTIES);

    @Test
    @DisplayName("액세스 토큰이 비어 있으면 OAUTH_ACCESS_TOKEN_IS_BLANK 예외를 던진다")
    void 액세스_토큰이_비어_있으면_예외가_발생한다() {
        assertThatThrownBy(() -> strategy.validateLoginRequest(SocialLoginRequest.of(" ")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OAUTH_ACCESS_TOKEN_IS_BLANK)
                .hasMessageContaining("OAuth 액세스 토큰이 비어 있습니다.");
    }

    @Test
    @DisplayName("이메일이 없으면 SOCIAL_EMAIL_NOT_PROVIDED 예외를 던진다")
    void 이메일이_없으면_예외가_발생한다() {
        assertThatThrownBy(() -> strategy.validateUserInfo(UserInfo.of("홍길동", null, "01011112222")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SOCIAL_EMAIL_NOT_PROVIDED)
                .hasMessageContaining("소셜 로그인 제공자가 이메일을 제공하지 않습니다.");
    }

    @Test
    @DisplayName("이름이 없으면 SOCIAL_NAME_NOT_PROVIDED 예외를 던진다")
    void 이름이_없으면_예외가_발생한다() {
        assertThatThrownBy(() -> strategy.validateUserInfo(UserInfo.of(null, "naver@test.com", "01011112222")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SOCIAL_NAME_NOT_PROVIDED)
                .hasMessageContaining("소셜 로그인 제공자가 이름을 제공하지 않습니다.");
    }

    @Test
    @DisplayName("연동 해제를 요청하면 네이버 토큰 폐기 API에 client 정보와 refresh token을 폼으로 보낸다")
    void 연동_해제를_요청하면_토큰_폐기_API에_폼으로_보낸다() {
        // given
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer naverServer = MockRestServiceServer.bindTo(builder).build();
        NaverLoginStrategy strategyWithServer = new NaverLoginStrategy(builder.build(), NAVER_PROPERTIES);
        String refreshToken = "c8ce+Mj/isO4Se==";
        naverServer.expect(requestTo(NAVER_WITHDRAW_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(Map.of(
                        "client_id", "naver-client-id",
                        "client_secret", "naver-client-secret",
                        "token", refreshToken,
                        "token_type_hint", "refresh_token")))
                .andRespond(withSuccess());

        // when & then
        assertThatCode(() -> strategyWithServer.withdrawSocialAccount(refreshToken, "naver-id"))
                .doesNotThrowAnyException();
        naverServer.verify();
    }

    @Test
    @DisplayName("네이버 토큰 폐기 API가 실패 상태를 응답하면 NAVER_WITHDRAW_ERROR 예외를 던진다")
    void 토큰_폐기_API가_실패_상태를_응답하면_예외가_발생한다() {
        // given
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer naverServer = MockRestServiceServer.bindTo(builder).build();
        NaverLoginStrategy strategyWithServer = new NaverLoginStrategy(builder.build(), NAVER_PROPERTIES);
        naverServer.expect(requestTo(NAVER_WITHDRAW_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"unauthorized_client\",\"error_description\":\"Client authentication failed.\"}"));

        // when & then
        assertThatThrownBy(() -> strategyWithServer.withdrawSocialAccount("refresh-token", "naver-id"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NAVER_WITHDRAW_ERROR);
    }
}
