package com.widyu.domain.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LocalSignupResponse(
        @Schema(description = "최초 가입 여부", example = "true") 
        boolean isFirst,
        
        @Schema(description = "액세스 토큰", example = "eyJhbGciOiJIUzI1NiJ9...") 
        String accessToken,
        
        @Schema(description = "리프레시 토큰", example = "eyJhbGciOiJIUzI1NiJ9...") 
        String refreshToken,
        
        @Schema(description = "사용자 프로필") 
        SignUpUserInfo profile
) {
    public static LocalSignupResponse ofTokenPair(TokenPairResponse tokenPair, SignUpUserInfo profile) {
        return new LocalSignupResponse(true, tokenPair.accessToken(), tokenPair.refreshToken(), 
                                     profile);
    }
}