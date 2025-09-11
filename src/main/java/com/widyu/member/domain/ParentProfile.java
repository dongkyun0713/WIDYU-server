package com.widyu.member.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
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
        name = "parent_profile",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_parent_invite_code", columnNames = "invite_code")
        }
)
public class ParentProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "guardian_id", nullable = false)
    private Member guardian;

    @Column(name = "birth_date")
    private String birthDate;

    private String address;

    @Column(name = "detail_address")
    private String detailAddress;

    @Column(name = "invite_code", nullable = false, unique = true, length = 7)
    private String inviteCode;

    @Builder(access = AccessLevel.PRIVATE)
    private ParentProfile(Member member, Member guardian, String birthDate, String address, String detailAddress, String inviteCode) {
        this.member = member;
        this.guardian = guardian;
        this.birthDate = birthDate;
        this.address = address;
        this.detailAddress = detailAddress;
        this.inviteCode = inviteCode;
    }

    public static ParentProfile createParentProfile(final Member member, final Member guardian, final String birthDate, final String address,
                                                    final String detailAddress, final String inviteCode) {
        return ParentProfile.builder()
                .member(member)
                .guardian(guardian)
                .birthDate(birthDate)
                .address(address)
                .detailAddress(detailAddress)
                .inviteCode(inviteCode)
                .build();
    }
}
