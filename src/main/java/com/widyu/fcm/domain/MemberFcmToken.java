package com.widyu.fcm.domain;

import com.widyu.member.domain.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemberFcmToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(nullable = false)
    private boolean active; // 현재 유효한 토큰인지 여부 (예: 로그아웃 시 false)

    private String deviceInfo; // 선택: 기기 정보 (e.g., Android, iOS 등)

    private LocalDateTime registeredAt;

    private LocalDateTime expiredAt;

    private LocalDateTime lastUsedAt;

    public void deactivate() {
        this.active = false;
        this.expiredAt = LocalDateTime.now();
    }

    public void activate() {
        this.active = true;
        this.expiredAt = null;
        this.lastUsedAt = LocalDateTime.now();
    }

    public void updateToken(String fcmToken, LocalDateTime now) {
        this.token = fcmToken;
        this.registeredAt = now;
        this.active = true;
        this.expiredAt = null;
    }
}
