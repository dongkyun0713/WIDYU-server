package com.widyu.auth.repository;

import com.widyu.auth.SocialUnlinkTask;
import com.widyu.auth.SocialUnlinkTaskStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SocialUnlinkTaskRepository extends JpaRepository<SocialUnlinkTask, Long> {

    @Query("select task.id from SocialUnlinkTask task where task.memberId = :memberId and task.status = :status")
    List<Long> findIdsByMemberIdAndStatus(@Param("memberId") Long memberId,
                                          @Param("status") SocialUnlinkTaskStatus status);

    @Query("""
            select task.id from SocialUnlinkTask task
            where task.status = :status and task.nextRetryAt <= :now
            order by task.id
            """)
    List<Long> findDueIds(@Param("status") SocialUnlinkTaskStatus status,
                          @Param("now") LocalDateTime now,
                          Pageable pageable);
}
