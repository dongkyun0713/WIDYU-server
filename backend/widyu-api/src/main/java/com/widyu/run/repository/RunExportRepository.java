package com.widyu.run.repository;

import com.widyu.run.RunExport;
import com.widyu.run.RunExportStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RunExportRepository extends JpaRepository<RunExport, Long> {

    Optional<RunExport> findByExportIdAndRunId(String exportId, String runId);

    List<RunExport> findByRunIdAndStatusInOrderByIdAsc(String runId, Collection<RunExportStatus> statuses);

    /** 큐에서 가장 오래 기다린 한 건. 선점은 {@link #claim(Long, long)}이 한다. */
    Optional<RunExport> findFirstByStatusOrderByRequestedAtMsAscIdAsc(RunExportStatus status);

    /**
     * 한 워커만 집도록 상태를 조건에 넣고 갱신한다. 1을 돌려주면 선점에 성공한 것이다.
     * {@code FcmOutboxDispatcher}와 같은 방식이다.
     */
    @Modifying
    @Query("""
            UPDATE RunExport e
               SET e.status = com.widyu.run.RunExportStatus.RUNNING,
                   e.startedAtMs = :startedAtMs
             WHERE e.id = :id
               AND e.status = com.widyu.run.RunExportStatus.QUEUED
            """)
    int claim(@Param("id") Long id, @Param("startedAtMs") long startedAtMs);
}
