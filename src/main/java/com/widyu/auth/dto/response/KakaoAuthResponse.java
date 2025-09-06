package com.widyu.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KakaoAuthResponse(
        Long id,
        @JsonProperty("kakao_account")
        KakaoAccount kakaoAccount
) {
    public record KakaoAccount(
            String email,
            String name,
            @JsonProperty("phone_number") String phoneNumber
    ) {}
}
