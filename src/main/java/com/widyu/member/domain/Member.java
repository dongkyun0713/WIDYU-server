package com.widyu.member.domain;

import com.widyu.fcm.domain.MemberFcmToken;
import com.widyu.global.domain.BaseTimeEntity;
import com.widyu.global.domain.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@EqualsAndHashCode(callSuper = false, of = "id")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    private MemberType type;

    private String name;

    private String phoneNumber;

    @OneToOne(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true)
    private LocalAccount localAccount;

    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SocialAccount> socialAccounts = new ArrayList<>();

    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MemberFcmToken> memberFcmTokens = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private Status status;

    @Builder(access = AccessLevel.PRIVATE)
    private Member(final MemberRole role, final MemberType type, final String name, final String phoneNumber,
                   final Status status) {
        this.role = role;
        this.type = type;
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.status = status;
    }

    public static Member createMember(final MemberType type, final String name, final String phoneNumber) {
        return Member.builder()
                .role(MemberRole.USER)
                .type(type)
                .name(name)
                .phoneNumber(phoneNumber)
                .status(Status.ACTIVE)
                .build();
    }

    public void markSocialAsNotFirst(String provider, String oauthId) {
        this.getSocialAccounts().stream()
                .filter(sa -> provider.equals(sa.getProvider()) && oauthId.equals(sa.getOauthId()))
                .findFirst()
                .ifPresent(SocialAccount::markNotFirst);
    }

    public SocialAccount getSocialAccount(String provider) {
        return this.getSocialAccounts().stream()
                .filter(sa -> provider.equals(sa.getProvider()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER));
    }

    public void updatePhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }
}
