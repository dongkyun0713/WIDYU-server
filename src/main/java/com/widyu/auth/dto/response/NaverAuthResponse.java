package com.widyu.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

public record NaverAuthResponse(
        String resultcode,
        String message,
        @JsonProperty("response") NaverUserResponse response
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NaverUserResponse(
            String id,
            String email,
            String name,
            @JsonProperty("mobile") String phoneNumber
    ) {}
}
