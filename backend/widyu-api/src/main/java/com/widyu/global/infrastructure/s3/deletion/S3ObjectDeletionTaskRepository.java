package com.widyu.global.infrastructure.s3.deletion;

import com.widyu.global.storage.S3ObjectDeletionTask;
import com.widyu.global.storage.S3ObjectDeletionTaskStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface S3ObjectDeletionTaskRepository extends JpaRepository<S3ObjectDeletionTask, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from S3ObjectDeletionTask task where task.id = :id")
    java.util.Optional<S3ObjectDeletionTask> findByIdForUpdate(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update S3ObjectDeletionTask task
            set task.status = com.widyu.global.storage.S3ObjectDeletionTaskStatus.PROCESSING,
                task.processingAttempt = task.processingAttempt + 1,
                task.leaseExpiresAt = :leaseExpiresAt
            where task.id = :id
              and ((task.status = com.widyu.global.storage.S3ObjectDeletionTaskStatus.PENDING
                    and task.nextRetryAt <= :now)
                   or (task.status = com.widyu.global.storage.S3ObjectDeletionTaskStatus.PROCESSING
                    and task.leaseExpiresAt < :now))
            """)
    int claimForProcessing(@Param("id") Long id,
                           @Param("now") LocalDateTime now,
                           @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    @Query("""
            select task.id
            from S3ObjectDeletionTask task
            where (task.status = :pendingStatus and task.nextRetryAt <= :now)
               or (task.status = :processingStatus and task.leaseExpiresAt < :now)
            order by task.id
            """)
    List<Long> findDueIds(@Param("pendingStatus") S3ObjectDeletionTaskStatus pendingStatus,
                          @Param("processingStatus") S3ObjectDeletionTaskStatus processingStatus,
                          @Param("now") LocalDateTime now,
                          Pageable pageable);
}
