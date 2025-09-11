package com.widyu.domain.auth.application.guardian.oauth.strategy;

public record UserInfo(
    String name,
    String email,
    String phoneNumber
) {
    public static UserInfo of(String name, String email, String phoneNumber) {
        return new UserInfo(name, email, phoneNumber);
    }
    
    public boolean hasName() {
        return name != null && !name.isBlank();
    }
    
    public boolean hasEmail() {
        return email != null && !email.isBlank();
    }
    
    public boolean hasPhoneNumber() {
        return phoneNumber != null && !phoneNumber.isBlank();
    }
}
