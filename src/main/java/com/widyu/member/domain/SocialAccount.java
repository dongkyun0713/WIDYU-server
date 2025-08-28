package com.widyu.member.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        uniqueConstraints = @UniqueConstraint(name = "uk_provider_user", columnNames = {"provider", "providerUserId"})
)
public class SocialAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    private String provider;

    private String oauthId;

    @Column(name = "is_first")
    private boolean isFirst;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Builder(access = AccessLevel.PRIVATE)
    private SocialAccount(String email, String provider, String oauthId, boolean isFirst, Member member) {
        this.email = email;
        this.provider = provider;
        this.oauthId = oauthId;
        this.isFirst = isFirst;
        this.member = member;
    }

    public static SocialAccount createSocialAccount(String email, String provider, String oauthId,
                                                    Member member) {
        return SocialAccount.builder()
                .email(email)
                .provider(provider)
                .oauthId(oauthId)
                .isFirst(true)
                .member(member)
                .build();
    }
}

