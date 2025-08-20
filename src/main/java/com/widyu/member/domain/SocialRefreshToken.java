package com.widyu.member.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SocialRefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String token;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "social_account_id", nullable = false)
    private SocialAccount socialAccount;

    @Builder(access = AccessLevel.PRIVATE)
    private SocialRefreshToken(String token, SocialAccount socialAccount) {
        this.token = token;
        this.socialAccount = socialAccount;
    }

    public static SocialRefreshToken createSocialRefreshToken(String token, SocialAccount socialAccount) {
        return SocialRefreshToken.builder()
                .token(token)
                .socialAccount(socialAccount)
                .build();
    }
}
