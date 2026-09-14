package com.widyu.auth.dto;

import com.widyu.member.MemberRole;
import lombok.Builder;

@Builder
public record AccessTokenDto(
        Long memberId,
        MemberRole memberRole,
        String loginType,
        String tokenValue,
        Long authVersion
) {
    public AccessTokenDto(Long memberId, MemberRole memberRole, String loginType, String tokenValue) {
        this(memberId, memberRole, loginType, tokenValue, null);
    }
    public static AccessTokenDto of(final Long memberId, final MemberRole memberRole, final String loginType, final String tokenValue) {
        return AccessTokenDto.builder()
                .memberId(memberId)
                .memberRole(memberRole)
                .loginType(loginType)
                .tokenValue(tokenValue)
                .build();
    }
}
