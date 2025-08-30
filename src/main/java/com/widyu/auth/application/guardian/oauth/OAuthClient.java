package com.widyu.auth.application.guardian.oauth;


import com.widyu.auth.domain.OAuthProvider;
import com.widyu.auth.dto.response.OAuthTokenResponse;
import com.widyu.auth.dto.response.SocialClientResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public interface OAuthClient {
    String getAuthCode(OAuthProvider provider, HttpServletResponse response) throws IOException;

    /**
     * 소셜 로그인 인증
     * auth code를 통해 소셜 access token과 refresh token을 발급받는다.
     * @param authCode 인증 코드
     * @return OAuth access 토큰과 refresh 토큰
     */
    OAuthTokenResponse getToken(String authCode, String state);

    /**
     * 소셜 로그인 인증e
     * oauth access token을 통해 사용자 정보를 가져온다.
     * @param accessToken 인증 토큰
     * @return 소셜 로그인 사용자 정보
     */
    SocialClientResponse getUserInfo(String accessToken);
}
