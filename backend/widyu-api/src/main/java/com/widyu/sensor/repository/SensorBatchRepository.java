package com.widyu.sensor.repository;

import com.widyu.sensor.SensorBatch;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SensorBatchRepository extends JpaRepository<SensorBatch, Long> {

    Optional<SensorBatch> findByBatchId(String batchId);

    /** 내보내기: 회차·스트림의 배치를 잰 시각 순으로(LLD-0050 5.3). */
    java.util.List<SensorBatch> findByRunIdAndStreamOrderByMeasuredAtStartMsAscSeqAsc(
            String runId, String stream);

    java.util.List<SensorBatch> findByRunIdOrderByMeasuredAtStartMsAsc(String runId);
}
