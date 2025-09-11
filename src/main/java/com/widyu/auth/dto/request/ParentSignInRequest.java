package com.widyu.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ParentSignInRequest(
        @NotBlank(message = "초대코드는 필수입니다.")
        @Pattern(regexp = "^\\d{7}$", message = "초대코드는 숫자 7자리여야 합니다.")
        String inviteCode,

        @NotBlank(message = "전화번호는 필수입니다.")
        @Pattern(regexp = "^\\d{10,11}$", message = "전화번호는 숫자 10~11자리여야 합니다.")
        String phoneNumber
) {
}
