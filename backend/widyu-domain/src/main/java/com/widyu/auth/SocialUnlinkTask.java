package com.widyu.auth;

import com.widyu.global.crypto.AesGcmStringConverter;
import com.widyu.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 탈퇴 회원의 소셜 연동 해제 작업. 탈퇴 트랜잭션에서 저장하고 커밋 뒤 처리하며, 실패하면 스케줄러가 다시 시도한다.
 * 끝나면(완료·최종 실패) 연동 해제에만 쓰던 oauthId·refresh token을 지운다. → LLD-0059
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "social_unlink_task",
        indexes = @Index(name = "idx_social_unlink_due", columnList = "status,next_retry_at"))
public class SocialUnlinkTask extends BaseTimeEntity {

    public static final int MAX_ATTEMPTS = 5;
    public static final int RETRY_DELAY_MINUTES = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "oauth_id")
    private String oauthId;

    @Convert(converter = AesGcmStringConverter.class)
    @Column(name = "refresh_token", length = 2048)
    private String refreshToken;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private SocialUnlinkTaskStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_retry_at", nullable = false)
    private LocalDateTime nextRetryAt;

    @Column(name = "last_error_type", length = 100)
    private String lastErrorType;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    private SocialUnlinkTask(Long memberId, String provider, String oauthId, String refreshToken,
                             LocalDateTime now) {
        this.memberId = memberId;
        this.provider = provider;
        this.oauthId = oauthId;
        this.refreshToken = refreshToken;
        this.status = SocialUnlinkTaskStatus.PENDING;
        this.nextRetryAt = now.plusMinutes(RETRY_DELAY_MINUTES);
    }

    public static SocialUnlinkTask pending(Long memberId, String provider, String oauthId, String refreshToken,
                                           LocalDateTime now) {
        return new SocialUnlinkTask(memberId, provider, oauthId, refreshToken, now);
    }

    public boolean isPending() {
        return status == SocialUnlinkTaskStatus.PENDING;
    }

    public void complete(LocalDateTime now) {
        this.status = SocialUnlinkTaskStatus.COMPLETED;
        this.completedAt = now;
        clearCredentials();
    }

    public void fail(String errorType, LocalDateTime now) {
        this.attemptCount++;
        this.lastErrorType = errorType;
        if (attemptCount >= MAX_ATTEMPTS) {
            this.status = SocialUnlinkTaskStatus.FAILED;
            this.failedAt = now;
            clearCredentials();
            return;
        }
        this.nextRetryAt = now.plusMinutes(RETRY_DELAY_MINUTES);
    }

    private void clearCredentials() {
        this.oauthId = null;
        this.refreshToken = null;
    }
}
