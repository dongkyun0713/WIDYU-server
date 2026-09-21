package com.widyu.fcm;

import com.widyu.global.entity.BaseTimeEntity;
import com.widyu.member.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FcmNotification extends BaseTimeEntity {

    // null은 원수신자를 알 수 없는 legacy 행을 위해 남겨둔다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_member_id", updatable = false)
    private Member recipientMember;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private FcmCategory fcmCategory;
    private String title;
    private String body;
    private boolean isRead;
    private String image;

    /** 이 알림을 낳은 판정. 연구 철회 시 다른 알림을 건드리지 않고 이 행만 찾는 연결키다. */
    @Column(name = "decision_id", length = 40)
    private String decisionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "memberFcmToken_id", nullable = false)
    private MemberFcmToken memberFcmToken;

    public void markAsRead() {
        this.isRead = true;
    }

}
