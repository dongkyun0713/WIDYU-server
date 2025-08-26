package com.widyu.member.domain;

import com.widyu.fcm.domain.MemberFcmToken;
import com.widyu.global.domain.BaseTimeEntity;
import com.widyu.global.domain.Status;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
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

    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SocialAccount> socialAccounts = new ArrayList<>();

    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MemberFcmToken> memberFcmTokens = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private Status status;

    @Builder(access = AccessLevel.PRIVATE)
    private Member(final MemberRole role, final MemberType type, final String name, final String phoneNumber,
                   final List<SocialAccount> socialAccounts, final Status status) {
        this.role = role;
        this.type = type;
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.socialAccounts = socialAccounts;
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
}
