package com.widyu.domain.auth.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AppleTokenRequest(
        @JsonProperty("client_id")
        String clientId,
        
        @JsonProperty("client_secret")
        String clientSecret,
        
        @JsonProperty("code")
        String code,
        
        @JsonProperty("grant_type")
        String grantType,
        
        @JsonProperty("redirect_uri")
        String redirectUri
) {
    public static AppleTokenRequest of(String clientId, String clientSecret, String code, String redirectUri) {
        return new AppleTokenRequest(clientId, clientSecret, code, "authorization_code", redirectUri);
    }
}