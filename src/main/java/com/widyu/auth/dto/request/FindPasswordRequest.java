package com.widyu.auth.dto.request;

public record FindPasswordRequest(
        String name,
        String email,
        String phoneNumber
) {
}
