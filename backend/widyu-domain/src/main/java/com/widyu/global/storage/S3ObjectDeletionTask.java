package com.widyu.global.storage;

import com.widyu.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
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

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "s3_object_deletion_task",
        indexes = @Index(name = "idx_s3_object_deletion_retry", columnList = "status,next_retry_at"))
public class S3ObjectDeletionTask extends BaseTimeEntity {
    private static final int MAX_RETRY_COUNT = 5;
    private static final int PROCESSING_LEASE_MINUTES = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "object_key", nullable = false, length = 1024)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private S3ObjectDeletionTaskStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "processing_attempt", nullable = false)
    private int processingAttempt;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "next_retry_at") private LocalDateTime nextRetryAt;
    @Column(name = "last_error_type", length = 100) private String lastErrorType;
    @Column(name = "completed_at") private LocalDateTime completedAt;
    @Column(name = "failed_at") private LocalDateTime failedAt;
    private S3ObjectDeletionTask(Long memberId, String objectKey) {
        this.memberId = memberId;
        this.objectKey = objectKey;
        this.status = S3ObjectDeletionTaskStatus.PENDING;
        this.nextRetryAt = LocalDateTime.now();
    }

    public static S3ObjectDeletionTask pending(Long memberId, String objectKey) {
        return new S3ObjectDeletionTask(memberId, objectKey);
    }

    public boolean isProcessing(int attempt) {
        return status == S3ObjectDeletionTaskStatus.PROCESSING && processingAttempt == attempt;
    }

    public void claim(LocalDateTime now) {
        status = S3ObjectDeletionTaskStatus.PROCESSING;
        processingAttempt++;
        leaseExpiresAt = now.plusMinutes(PROCESSING_LEASE_MINUTES);
    }

    public void complete() {
        status = S3ObjectDeletionTaskStatus.COMPLETED;
        completedAt = LocalDateTime.now();
        nextRetryAt = null;
        leaseExpiresAt = null;
    }

    public void fail(String errorType) {
        retryCount++;
        lastErrorType = errorType;
        leaseExpiresAt = null;
        if (retryCount >= MAX_RETRY_COUNT) {
            status = S3ObjectDeletionTaskStatus.FAILED;
            failedAt = LocalDateTime.now();
            nextRetryAt = null;
            return;
        }
        status = S3ObjectDeletionTaskStatus.PENDING;
        nextRetryAt = LocalDateTime.now().plusMinutes(5);
    }
}
