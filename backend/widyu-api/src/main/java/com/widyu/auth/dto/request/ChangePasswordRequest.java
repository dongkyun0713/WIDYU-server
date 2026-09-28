package com.widyu.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "비밀번호는 필수입니다")
        @Size(min = 8, max = 12, message = "비밀번호는 8~12자 사이여야 합니다")
        @Pattern(regexp = "^(?=.*[a-zA-Z])(?=.*\\d)(?=.*[!@#$%^&*(),.?\":{}|<>]).{8,12}$",
                message = "비밀번호는 영문, 숫자, 특수기호를 모두 포함해야 합니다")
        String password
) {
}
