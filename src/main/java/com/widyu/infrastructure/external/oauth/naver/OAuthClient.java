package com.widyu.infrastructure.external.oauth.naver;


import com.widyu.auth.dto.response.SocialClientResponse;

public interface OAuthClient {
    /**
     * 소셜 로그인 인증e
     * oauth access token을 통해 사용자 정보를 가져온다.
     * @param accessToken 인증 토큰
     * @return 소셜 로그인 사용자 정보
     */
    SocialClientResponse getUserInfo(String accessToken);
}
