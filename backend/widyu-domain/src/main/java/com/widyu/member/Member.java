package com.widyu.member;

import com.widyu.addressbookmark.AddressBookmark;
import com.widyu.fcm.MemberFcmToken;
import com.widyu.fcm.MemberNotificationSetting;
import com.widyu.global.entity.BaseTimeEntity;
import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
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
    @Column(nullable = false)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberType type;

    @Column(nullable = false)
    private String name;

    private String phoneNumber;

    private String profileImage;

    @OneToOne(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true)
    private LocalAccount localAccount;

    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SocialAccount> socialAccounts = new ArrayList<>();

    // 시니어일 경우: 본인의 프로필
    @OneToOne(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true)
    private SeniorProfile seniorProfile;

    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MemberFcmToken> memberFcmTokens = new ArrayList<>();

    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MemberNotificationSetting> notificationSettings = new ArrayList<>();

    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AddressBookmark> addressBookmarks = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private long medicationAlarmRevision;

    @Builder(access = AccessLevel.PRIVATE)
    private Member(final MemberRole role, final MemberType type, final String name, final String phoneNumber,
                   final String profileImage, final Status status) {
        this.role = role;
        this.type = type;
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.profileImage = profileImage;
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

    public static Member createAdminMember(final String name, final String phoneNumber) {
        return Member.builder()
                .role(MemberRole.ADMIN)
                .type(MemberType.GUARDIAN)
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

    public void updateName(String name) {
        this.name = name;
    }

    public void updateProfileImage(String profileImage) {
        this.profileImage = profileImage;
    }

    public void withdraw() {
        this.status = Status.INACTIVE;
    }

    public void reactivate() {
        this.status = Status.ACTIVE;
    }

    public long incrementMedicationAlarmRevision() {
        medicationAlarmRevision++;
        return medicationAlarmRevision;
    }

    public void maskPersonalInfo() {
        this.name = "탈퇴회원";
        this.phoneNumber = null;
        
        if (this.localAccount != null) {
            this.localAccount.maskEmail();
        }
        
        this.socialAccounts.forEach(SocialAccount::maskPersonalInfo);
    }
}
