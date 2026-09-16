package com.widyu.fcm.repository;

import com.widyu.fcm.FcmOutbox;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface FcmOutboxRepository extends JpaRepository<FcmOutbox, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from FcmOutbox o where o.id = :id")
    Optional<FcmOutbox> lockById(Long id);

    @Query("""
            select o.id from FcmOutbox o
            where (o.state = com.widyu.fcm.FcmOutbox.State.PENDING and (o.availableAt <= :now or o.expiresAt <= :now))
               or (o.state = com.widyu.fcm.FcmOutbox.State.CLAIMED and o.leaseUntil <= :now)
            order by o.id
            """)
    List<Long> findDue(LocalDateTime now, Pageable pageable);
}
