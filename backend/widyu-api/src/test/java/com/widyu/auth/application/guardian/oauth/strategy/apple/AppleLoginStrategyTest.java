package com.widyu.auth.application.guardian.oauth.strategy.apple;

import static com.widyu.global.constant.SecurityConstant.APPLE_TOKEN_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.auth.application.guardian.oauth.strategy.UserInfo;
import com.widyu.auth.dto.request.SocialLoginRequest;
import com.widyu.auth.dto.request.SocialLoginRequest.AppleProfile;
import com.widyu.auth.dto.response.SocialClientResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.AppleProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("AppleLoginStrategy 예외 처리 단위 테스트")
class AppleLoginStrategyTest {

    private final AppleLoginStrategy strategy = new AppleLoginStrategy(
            new AppleProperties("ios", "android", "team", "key", "private-key", "redirect-uri"),
            mock(AppleJwtUtils.class),
            RestClient.builder().build(),
            new ObjectMapper()
    );

    @Test
    @DisplayName("인증 코드가 비어 있으면 APPLE_AUTHORIZATION_CODE_IS_BLANK 예외를 던진다")
    void 인증_코드가_비어_있으면_예외가_발생한다() {
        assertThatThrownBy(() -> strategy.validateLoginRequest(SocialLoginRequest.builder().authorizationCode(" ").build()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.APPLE_AUTHORIZATION_CODE_IS_BLANK)
                .hasMessageContaining("애플 인증 코드가 비어 있습니다.");
    }

    @Test
    @DisplayName("이메일이 없으면 SOCIAL_EMAIL_NOT_PROVIDED 예외를 던진다")
    void 이메일이_없으면_예외가_발생한다() {
        assertThatThrownBy(() -> strategy.validateUserInfo(UserInfo.of("익명의 사용자", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SOCIAL_EMAIL_NOT_PROVIDED)
                .hasMessageContaining("소셜 로그인 제공자가 이메일을 제공하지 않습니다.");
    }

    @Test
    @DisplayName("클라이언트가 다른 이메일을 profile로 보내면 id_token의 이메일을 사용하고 이름은 profile 값을 쓴다")
    void 클라이언트가_다른_이메일을_보내면_id_token_이메일을_사용한다() {
        // given
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer appleServer = MockRestServiceServer.bindTo(builder).build();
        AppleLoginStrategy strategyWithServer = new AppleLoginStrategy(
                new AppleProperties("ios", "android", "team", "key", "private-key", "redirect-uri"),
                mock(AppleJwtUtils.class),
                builder.build(),
                new ObjectMapper()
        );
        appleServer.expect(requestTo(APPLE_TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(appleTokenResponse("apple-sub", "owner@privaterelay.appleid.com"),
                        MediaType.APPLICATION_JSON));
        SocialLoginRequest request = SocialLoginRequest.builder()
                .authorizationCode("code")
                .platform("ios")
                .profile(new AppleProfile("victim@example.com", "홍길동"))
                .build();

        // when
        SocialClientResponse response = strategyWithServer.getUserInfo(request);
        UserInfo userInfo = strategyWithServer.processUserInfo(response, request);

        // then
        assertThat(response.oauthId()).isEqualTo("apple-sub");
        assertThat(userInfo.email()).isEqualTo("owner@privaterelay.appleid.com");
        assertThat(userInfo.name()).isEqualTo("홍길동");
        appleServer.verify();
    }

    @Test
    @DisplayName("id_token에 이메일이 없으면 profile 이메일로 채우지 않아 SOCIAL_EMAIL_NOT_PROVIDED 예외가 발생한다")
    void id_token에_이메일이_없으면_profile_이메일로_채우지_않는다() {
        // given
        SocialClientResponse idTokenWithoutEmail = SocialClientResponse.of("apple-sub", null, null, null, "refresh");
        SocialLoginRequest request = SocialLoginRequest.builder()
                .authorizationCode("code")
                .profile(new AppleProfile("victim@example.com", "홍길동"))
                .build();

        // when
        UserInfo userInfo = strategy.processUserInfo(idTokenWithoutEmail, request);

        // then
        assertThat(userInfo.email()).isNull();
        assertThatThrownBy(() -> strategy.validateUserInfo(userInfo))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SOCIAL_EMAIL_NOT_PROVIDED);
    }

    private String appleTokenResponse(String subject, String email) {
        String payload = "{\"sub\":\"" + subject + "\",\"email\":\"" + email + "\"}";
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String idToken = "header." + encodedPayload + ".signature";
        return "{\"access_token\":\"a\",\"token_type\":\"bearer\",\"expires_in\":3600,"
                + "\"refresh_token\":\"r\",\"id_token\":\"" + idToken + "\"}";
    }
}
