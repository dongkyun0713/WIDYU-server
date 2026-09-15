package com.widyu.fcm;

import com.widyu.global.entity.BaseTimeEntity;
import com.widyu.member.Member;
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

    // Null is reserved for legacy rows whose original recipient is unknown.
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "memberFcmToken_id", nullable = false)
    private MemberFcmToken memberFcmToken;

    public void markAsRead() {
        this.isRead = true;
    }

}
