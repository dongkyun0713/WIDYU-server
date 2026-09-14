package com.widyu.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LocalGuardianSignInRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 256) String password
) {
}
