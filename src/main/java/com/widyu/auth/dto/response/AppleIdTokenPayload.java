package com.widyu.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AppleIdTokenPayload(
        @JsonProperty("iss")
        String issuer,
        
        @JsonProperty("aud")
        String audience,
        
        @JsonProperty("exp")
        Long expiration,
        
        @JsonProperty("iat")
        Long issuedAt,
        
        @JsonProperty("sub")
        String subject,
        
        @JsonProperty("email")
        String email,
        
        @JsonProperty("email_verified")
        Boolean emailVerified,
        
        @JsonProperty("is_private_email")
        Boolean isPrivateEmail
) {
}