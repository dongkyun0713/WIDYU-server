package com.widyu.auth.dto.request;

public record LocalGuardianSignInRequest(
        String email,
        String password
) {
}
