package com.widyu.auth.dto.response;

import com.widyu.global.util.PiiMaskingUtil;
import com.widyu.member.Member;
import lombok.Builder;

@Builder
public record MemberInfoResponse(
        Long memberId,
        String name,
        String phoneNumber,
        String email
) {
    // 계정 찾기 응답. 본인 확인용 힌트만 제공하도록 전화번호·이메일을 마스킹한다.
    public static MemberInfoResponse from(
            final Member member
            ) {
        return MemberInfoResponse.builder()
                .memberId(member.getId())
                .name(member.getName())
                .phoneNumber(PiiMaskingUtil.maskPhoneNumber(member.getPhoneNumber()))
                .email(PiiMaskingUtil.maskEmail(member.getLocalAccount().getEmail()))
                .build();
    }
}
