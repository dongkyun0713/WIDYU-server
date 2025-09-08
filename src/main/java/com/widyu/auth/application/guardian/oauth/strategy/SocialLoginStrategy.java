package com.widyu.auth.application.guardian.oauth.strategy;

import com.widyu.auth.domain.OAuthProvider;
import com.widyu.auth.dto.request.SocialLoginRequest;
import com.widyu.auth.dto.response.SocialClientResponse;

public interface SocialLoginStrategy {
    
    /**
     * 지원하는 OAuth 제공자 반환
     */
    OAuthProvider getSupportedProvider();
    
    /**
     * 소셜 로그인 요청 검증
     */
    void validateLoginRequest(SocialLoginRequest request);
    
    /**
     * 제공자로부터 사용자 정보 획득
     */
    SocialClientResponse getUserInfo(SocialLoginRequest request);
    
    /**
     * 제공자별 사용자 정보 후처리
     */
    UserInfo processUserInfo(SocialClientResponse socialResponse, SocialLoginRequest request);
    
    /**
     * 사용자 정보 검증 (제공자별 필수 필드가 다름)
     */
    void validateUserInfo(UserInfo userInfo);
    
    /**
     * 소셜 계정 탈퇴 (제공자별 구현)
     */
    void withdrawSocialAccount(String accessToken, String oauthId);
}
