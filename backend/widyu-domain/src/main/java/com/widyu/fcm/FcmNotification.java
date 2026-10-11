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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_fcm_notification_recipient_event",
        columnNames = {"recipient_member_id", "event_id"}))
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

    @Column(name = "event_id", length = 40)
    private String eventId;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "type", length = 48)
    private NotificationType type;
    @Column(name = "deep_link")
    private String deepLink;
    @Column(name = "entity_id")
    private String entityId;
    @Column(name = "senior_id")
    private Long seniorId;
    @Column(name = "actor_display_name")
    private String actorDisplayName;
    @Column(name = "senior_display_name")
    private String seniorDisplayName;
    @Column(name = "remaining_locked_count")
    private Integer remainingLockedCount;
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
    @Column(name = "retention_policy_version", length = 32)
    private String retentionPolicyVersion;
    @Column(name = "push_eligible")
    private Boolean pushEligible;
    @Column(name = "read_at")
    private LocalDateTime readAt;

    /** 이 알림을 낳은 판정. 연구 철회 시 다른 알림을 건드리지 않고 이 행만 찾는 연결키다. */
    @Column(name = "decision_id", length = 40)
    private String decisionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "memberFcmToken_id")
    private MemberFcmToken memberFcmToken;

    public void markAsRead() {
        this.isRead = true;
        if (readAt == null) {
            readAt = LocalDateTime.now();
        }
    }

}
