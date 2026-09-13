package com.widyu.mypage.dto.response;

import com.widyu.member.Member;
import java.util.List;

public record GuardianProfileDetailResponse(
        String profileImage,
        String name,
        String phoneNumber,
        String email,
        List<String> socialProviders
) {
    public static GuardianProfileDetailResponse from(Member member) {
        String email = getEmail(member);

        List<String> providers = member.getSocialAccounts().stream()
                .map(sa -> sa.getProvider())
                .toList();

        return new GuardianProfileDetailResponse(
                member.getProfileImage(),
                member.getName(),
                member.getPhoneNumber(),
                email,
                providers
        );
    }

    private static String getEmail(Member member) {
        if (member.getLocalAccount() != null) {
            return member.getLocalAccount().getEmail();
        }
        return member.getSocialAccounts().stream()
                .map(sa -> sa.getEmail())
                .filter(e -> e != null && !e.isBlank())
                .findFirst()
                .orElse(null);
    }
}
