package com.widyu.fcm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SendNotificationRequest(
        @NotNull(message = "받는 사람 ID는 필수입니다.")
        Long receiverId,

        @NotBlank(message = "알림 내용은 필수입니다.")
        @Size(max = 255, message = "알림 내용은 255자 이하로 입력해 주세요.")
        String content
) {
}
