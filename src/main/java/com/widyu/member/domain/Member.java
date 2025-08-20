package com.widyu.member.domain;

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

    private String phone;

    @OneToOne(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true)
    private LocalAccount localAccount;

    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SocialAccount> socialAccounts = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private Status status;

    @Builder(access = AccessLevel.PRIVATE)

    public Member(MemberRole role, MemberType type, String name, String phone, LocalAccount localAccount,
                  List<SocialAccount> socialAccounts, Status status) {
        this.role = role;
        this.type = type;
        this.name = name;
        this.phone = phone;
        this.localAccount = localAccount;
        this.socialAccounts = socialAccounts;
        this.status = status;
    }

    public static Member createMember(MemberRole role, MemberType type, String name, String phone,
                                      LocalAccount localAccount, List<SocialAccount> socialAccounts) {
        return Member.builder()
                .role(role)
                .type(type)
                .name(name)
                .phone(phone)
                .localAccount(localAccount)
                .socialAccounts(socialAccounts)
                .status(Status.ACTIVE)
                .build();
    }
}
