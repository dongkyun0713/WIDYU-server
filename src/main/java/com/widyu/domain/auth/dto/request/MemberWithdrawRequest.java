package com.widyu.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record MemberWithdrawRequest(
        @NotBlank(message = "탈퇴 사유는 필수입니다")
        String reason,
        
        Map<String, String> socialAccessTokens
) {
    public static MemberWithdrawRequest of(String reason, Map<String, String> socialAccessTokens) {
        return new MemberWithdrawRequest(reason, socialAccessTokens);
    }
}