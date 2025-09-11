package com.widyu.domain.auth.dto.request;

public record LocalGuardianSignInRequest(
        String email,
        String password
) {
}
