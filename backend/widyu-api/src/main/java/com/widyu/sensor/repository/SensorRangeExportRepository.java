package com.widyu.sensor.repository;

import com.widyu.sensor.SensorRangeExport;
import com.widyu.sensor.SensorRangeExportStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SensorRangeExportRepository extends JpaRepository<SensorRangeExport, Long> {

    Optional<SensorRangeExport> findByExportIdAndMemberId(String exportId, Long memberId);

    List<SensorRangeExport> findByMemberIdAndStartAtMsAndEndAtMsAndStatusInOrderByIdAsc(
            Long memberId, Long startAtMs, Long endAtMs, Collection<SensorRangeExportStatus> statuses);

    Optional<SensorRangeExport> findFirstByStatusOrderByRequestedAtMsAscIdAsc(SensorRangeExportStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE SensorRangeExport e
               SET e.status = com.widyu.sensor.SensorRangeExportStatus.RUNNING,
                   e.startedAtMs = :startedAtMs
             WHERE e.id = :id
               AND e.status = com.widyu.sensor.SensorRangeExportStatus.QUEUED
            """)
    int claim(@Param("id") Long id, @Param("startedAtMs") long startedAtMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE SensorRangeExport e
               SET e.status = com.widyu.sensor.SensorRangeExportStatus.QUEUED,
                   e.startedAtMs = null
             WHERE e.status = com.widyu.sensor.SensorRangeExportStatus.RUNNING
               AND e.startedAtMs < :expiredBeforeMs
            """)
    int requeueExpiredRunning(@Param("expiredBeforeMs") long expiredBeforeMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE SensorRangeExport e
               SET e.status = com.widyu.sensor.SensorRangeExportStatus.DONE,
                   e.s3Key = :s3Key, e.bytes = :bytes, e.sha256 = :sha256,
                   e.finishedAtMs = :finishedAtMs
             WHERE e.id = :id
               AND e.status = com.widyu.sensor.SensorRangeExportStatus.RUNNING
               AND e.startedAtMs = :startedAtMs
            """)
    int finishIfRunning(@Param("id") Long id, @Param("startedAtMs") long startedAtMs,
                        @Param("s3Key") String s3Key, @Param("bytes") long bytes,
                        @Param("sha256") String sha256, @Param("finishedAtMs") long finishedAtMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE SensorRangeExport e
               SET e.status = com.widyu.sensor.SensorRangeExportStatus.FAILED,
                   e.errorType = :errorType, e.finishedAtMs = :finishedAtMs
             WHERE e.id = :id
               AND e.status = com.widyu.sensor.SensorRangeExportStatus.RUNNING
               AND e.startedAtMs = :startedAtMs
            """)
    int failIfRunning(@Param("id") Long id, @Param("startedAtMs") long startedAtMs,
                      @Param("errorType") String errorType, @Param("finishedAtMs") long finishedAtMs);
}
