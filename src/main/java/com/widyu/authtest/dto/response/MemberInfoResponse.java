package com.widyu.authtest.dto.response;

import com.widyu.member.domain.Member;
import lombok.Builder;

@Builder
public record MemberInfoResponse(
        Long memberId,
        String name,
        String phoneNumber,
        String email
) {
    public static MemberInfoResponse from(
            final Member member
            ) {
        return MemberInfoResponse.builder()
                .memberId(member.getId())
                .name(member.getName())
                .phoneNumber(member.getPhoneNumber())
                .email(member.getLocalAccount().getEmail())
                .build();
    }
}
