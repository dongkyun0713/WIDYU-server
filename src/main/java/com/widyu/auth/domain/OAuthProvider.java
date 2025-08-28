package com.widyu.auth.domain;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OAuthProvider {
    KAKAO("kakao"),
    NAVER("naver"),
    APPLE("APPLE"),
    ;
    private final String value;

    public static OAuthProvider from(final String provider) {
        return switch (provider.toUpperCase()) {
            case "KAKAO" -> KAKAO;
            case "NAVER" -> NAVER;
            case "APPLE" -> APPLE;
            default -> throw new BusinessException(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER, ": " + provider);
        };
    }
}
