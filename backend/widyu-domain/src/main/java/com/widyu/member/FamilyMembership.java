package com.widyu.member;

import com.widyu.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "family_membership")
public class FamilyMembership extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "family_id", nullable = false)
    private Family family;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "guardian_id", nullable = false, unique = true)
    private Member guardian;

    @Column(name = "connected_at", nullable = false)
    private LocalDateTime connectedAt;

    private String nickname;

    @Column(name = "is_representative", nullable = false)
    private boolean isRepresentative = false;

    @Column(name = "is_leader", nullable = false)
    private boolean isLeader = false;

    @Builder(access = AccessLevel.PRIVATE)
    private FamilyMembership(Family family, Member guardian, LocalDateTime connectedAt, String nickname,
                              boolean isRepresentative, boolean isLeader) {
        this.family = family;
        this.guardian = guardian;
        this.connectedAt = connectedAt;
        this.nickname = nickname;
        this.isRepresentative = isRepresentative;
        this.isLeader = isLeader;
    }

    public static FamilyMembership createMembership(Family family, Member guardian) {
        return FamilyMembership.builder()
                .family(family)
                .guardian(guardian)
                .connectedAt(LocalDateTime.now())
                .isRepresentative(false)
                .isLeader(false)
                .build();
    }

    public static FamilyMembership createLeaderMembership(Family family, Member guardian) {
        return FamilyMembership.builder()
                .family(family)
                .guardian(guardian)
                .connectedAt(LocalDateTime.now())
                .isRepresentative(true)
                .isLeader(true)
                .build();
    }

    public void updateNickname(String nickname) {
        this.nickname = nickname;
    }

    public void setRepresentative(boolean representative) {
        this.isRepresentative = representative;
    }

    public void setLeader(boolean leader) {
        this.isLeader = leader;
    }
}
