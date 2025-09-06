package com.widyu.global.constant;

public final class SecurityConstant {

    // security
    public static final String TOKEN_ROLE_NAME = "role";
    public static final String TOKEN_PREFIX = "Bearer ";

    // naver
    public static final String NAVER_AUTH_URL = "https://nid.naver.com/oauth2.0/authorize";
    public static final String NAVER_TOKEN_URL = "https://nid.naver.com/oauth2.0/token";
    public static final String NAVER_USER_ME_URL = "https://openapi.naver.com/v1/nid/me";

    // kakao
    public static final String KAKAO_AUTH_URL = "https://kauth.kakao.com/oauth/authorize";
    public static final String KAKAO_TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    public static final String KAKAO_USER_ME_URL = "https://kapi.kakao.com/v2/user/me";

    // apple
    public static final String APPLE_TOKEN_URL = "https://appleid.apple.com/auth/token";
    public static final String APPLE_ISSUER = "https://appleid.apple.com";

    private SecurityConstant() {}
}
