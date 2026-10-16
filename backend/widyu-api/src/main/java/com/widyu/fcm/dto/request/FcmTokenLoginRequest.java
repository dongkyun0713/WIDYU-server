package com.widyu.fcm.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties("deviceInfo")
public record FcmTokenLoginRequest(
        @NotBlank(message = "FCM 토큰은 필수입니다.")
        @Size(max = 255, message = "FCM 토큰은 255자 이하여야 합니다.")
        String token
) {
}
