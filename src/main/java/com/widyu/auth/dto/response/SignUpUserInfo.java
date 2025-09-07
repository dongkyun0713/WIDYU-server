package com.widyu.auth.dto.response;

public record SignUpUserInfo(
        String name,
        String phoneNumber,
        String email
) {
    public static SignUpUserInfo of(String name, String phoneNumber, String email) {
        return new SignUpUserInfo(name, phoneNumber, email);
    }
}
