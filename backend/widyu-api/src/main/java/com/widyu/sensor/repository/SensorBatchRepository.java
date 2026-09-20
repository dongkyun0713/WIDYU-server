package com.widyu.sensor.repository;

import com.widyu.sensor.SensorBatch;
import java.util.Optional;
import java.util.Collection;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SensorBatchRepository extends JpaRepository<SensorBatch, Long> {

    Optional<SensorBatch> findByBatchId(String batchId);

    /** 내보내기: 회차·스트림의 배치를 잰 시각 순으로(LLD-0050 5.3). */
    java.util.List<SensorBatch> findByRunIdAndStreamOrderByMeasuredAtStartMsAscSeqAsc(
            String runId, String stream);

    java.util.List<SensorBatch> findByRunIdOrderByMeasuredAtStartMsAsc(String runId);

    @Query("""
            SELECT batch FROM SensorBatch batch
             WHERE batch.member.id = :memberId
               AND batch.stream IN :streams
               AND batch.measuredAtEndMs >= :windowStartMs
               AND batch.measuredAtStartMs <= :windowEndMs
               AND batch.persistedAtMs <= :inputCutoffMs
             ORDER BY batch.measuredAtStartMs ASC, batch.seq ASC
            """)
    java.util.List<SensorBatch> findFallInputBatches(
            @Param("memberId") Long memberId,
            @Param("streams") Collection<String> streams,
            @Param("windowStartMs") long windowStartMs,
            @Param("windowEndMs") long windowEndMs,
            @Param("inputCutoffMs") long inputCutoffMs);
}
