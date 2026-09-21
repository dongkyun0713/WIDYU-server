package com.widyu.admin.repository;

import com.widyu.admin.AdminAccessLog;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminAccessLogRepository extends JpaRepository<AdminAccessLog, Long> {

    @Query("""
            select l from AdminAccessLog l
            where (:adminId is null or l.adminId = :adminId)
              and (:from is null or l.accessedAt >= :from)
              and (:to is null or l.accessedAt <= :to)
            order by l.id desc
            """)
    Page<AdminAccessLog> search(@Param("adminId") Long adminId,
                                @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to,
                                Pageable pageable);
}
