package com.widyu.authtest.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KakaoAuthResponse(
        Long id,
        @JsonProperty("kakao_account")
        KakaoAccount kakaoAccount
) {
    public record KakaoAccount(
            String email,
            KakaoProfile profile,
            @JsonProperty("phone_number") String phoneNumber
    ) {}

    public record KakaoProfile(
            String nickname
    ) {}
}
