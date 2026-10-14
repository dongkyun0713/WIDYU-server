package com.widyu.fcm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FcmTokenLoginRequest(
        @NotBlank(message = "FCM 토큰은 필수입니다.")
        @Size(max = 255, message = "FCM 토큰은 255자 이하여야 합니다.")
        String token,

        @Size(max = 255, message = "기기 정보는 255자 이하로 입력해 주세요.")
        String deviceInfo
) {
}
