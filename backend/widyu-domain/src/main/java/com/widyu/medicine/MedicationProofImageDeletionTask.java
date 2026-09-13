package com.widyu.medicine;

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
@Table(name = "medication_proof_image_deletion_task",
        indexes = @Index(name = "idx_proof_image_deletion_retry", columnList = "status,next_retry_at"))
public class MedicationProofImageDeletionTask extends BaseTimeEntity {
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
    private MedicationProofImageDeletionTaskStatus status;

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
    private MedicationProofImageDeletionTask(Long memberId, String objectKey) {
        this.memberId = memberId;
        this.objectKey = objectKey;
        this.status = MedicationProofImageDeletionTaskStatus.PENDING;
        this.nextRetryAt = LocalDateTime.now();
    }

    public static MedicationProofImageDeletionTask pending(Long memberId, String objectKey) {
        return new MedicationProofImageDeletionTask(memberId, objectKey);
    }

    public boolean isProcessing(int attempt) {
        return status == MedicationProofImageDeletionTaskStatus.PROCESSING && processingAttempt == attempt;
    }

    public void claim(LocalDateTime now) {
        status = MedicationProofImageDeletionTaskStatus.PROCESSING;
        processingAttempt++;
        leaseExpiresAt = now.plusMinutes(PROCESSING_LEASE_MINUTES);
    }

    public void complete() {
        status = MedicationProofImageDeletionTaskStatus.COMPLETED;
        completedAt = LocalDateTime.now();
        nextRetryAt = null;
        leaseExpiresAt = null;
    }

    public void fail(String errorType) {
        retryCount++;
        lastErrorType = errorType;
        leaseExpiresAt = null;
        if (retryCount >= MAX_RETRY_COUNT) {
            status = MedicationProofImageDeletionTaskStatus.FAILED;
            failedAt = LocalDateTime.now();
            nextRetryAt = null;
            return;
        }
        status = MedicationProofImageDeletionTaskStatus.PENDING;
        nextRetryAt = LocalDateTime.now().plusMinutes(5);
    }
}
