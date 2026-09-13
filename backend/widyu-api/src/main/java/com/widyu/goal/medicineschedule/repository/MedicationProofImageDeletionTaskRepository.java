package com.widyu.goal.medicineschedule.repository;

import com.widyu.medicine.MedicationProofImageDeletionTask;
import com.widyu.medicine.MedicationProofImageDeletionTaskStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MedicationProofImageDeletionTaskRepository extends JpaRepository<MedicationProofImageDeletionTask, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from MedicationProofImageDeletionTask task where task.id = :id")
    java.util.Optional<MedicationProofImageDeletionTask> findByIdForUpdate(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MedicationProofImageDeletionTask task
            set task.status = com.widyu.medicine.MedicationProofImageDeletionTaskStatus.PROCESSING,
                task.processingAttempt = task.processingAttempt + 1,
                task.leaseExpiresAt = :leaseExpiresAt
            where task.id = :id
              and ((task.status = com.widyu.medicine.MedicationProofImageDeletionTaskStatus.PENDING
                    and task.nextRetryAt <= :now)
                   or (task.status = com.widyu.medicine.MedicationProofImageDeletionTaskStatus.PROCESSING
                    and task.leaseExpiresAt < :now))
            """)
    int claimForProcessing(@Param("id") Long id,
                           @Param("now") LocalDateTime now,
                           @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    @Query("""
            select task.id
            from MedicationProofImageDeletionTask task
            where (task.status = :pendingStatus and task.nextRetryAt <= :now)
               or (task.status = :processingStatus and task.leaseExpiresAt < :now)
            order by task.id
            """)
    List<Long> findDueIds(@Param("pendingStatus") MedicationProofImageDeletionTaskStatus pendingStatus,
                          @Param("processingStatus") MedicationProofImageDeletionTaskStatus processingStatus,
                          @Param("now") LocalDateTime now,
                          Pageable pageable);
}
