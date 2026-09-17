package com.widyu.fcm;

import com.widyu.global.entity.BaseTimeEntity;
import com.widyu.member.Member;
import jakarta.persistence.*;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.*;

@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(indexes = @Index(name = "idx_fcm_outbox_due", columnList = "state,availableAt,leaseUntil"))
public class FcmOutbox extends BaseTimeEntity {
    public enum State { PENDING, CLAIMED, SENT, CANCELLED, EXHAUSTED, EXPIRED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_member_id", nullable = false, updatable = false)
    private Member recipientMember;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_fcm_token_id", nullable = false, updatable = false)
    private MemberFcmToken memberFcmToken;
    private Long relatedMemberId;
    private Long familyId;
    private String title;
    private String body;
    private String image;
    private String scheme;
    @Column(length = 100)
    private String dataType;
    private Long dataRevision;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private FcmCategory fcmCategory;
    @Column(nullable = false)
    private boolean emergency;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    private State state;
    @Column(nullable = false)
    private int attempts;
    @Column(nullable = false)
    private long fence;
    @Column(nullable = false)
    private LocalDateTime availableAt;
    @Column(nullable = false)
    private LocalDateTime expiresAt;
    private LocalDateTime leaseUntil;

    public boolean claim(LocalDateTime now, Duration lease, int maxRetries) {
        if (state != State.PENDING && state != State.CLAIMED) {
            return false;
        }
        if (state == State.CLAIMED && leaseUntil.isAfter(now)) {
            return false;
        }
        if (!expiresAt.isAfter(now)) {
            state = State.EXPIRED;
            return false;
        }
        if (availableAt.isAfter(now)) {
            return false;
        }
        if (attempts > maxRetries) {
            state = State.EXHAUSTED;
            return false;
        }
        state = State.CLAIMED;
        attempts++;
        fence++;
        leaseUntil = now.plus(lease);
        return true;
    }

    public boolean owns(long expectedFence, LocalDateTime now) {
        return state == State.CLAIMED && fence == expectedFence && leaseUntil.isAfter(now);
    }

    public void cancel() { state = State.CANCELLED; }
    public void expire() { state = State.EXPIRED; }
    public void sent() { state = State.SENT; }

    public void failed(boolean retryable, Duration delay, LocalDateTime now, int maxRetries) {
        if (!expiresAt.isAfter(now)) {
            state = State.EXPIRED;
        } else if (!retryable || attempts > maxRetries) {
            state = State.EXHAUSTED;
        } else {
            state = State.PENDING;
            if (delay.compareTo(Duration.between(now, expiresAt)) >= 0) {
                availableAt = expiresAt;
            } else {
                availableAt = now.plus(delay);
            }
        }
        leaseUntil = null;
    }
}
