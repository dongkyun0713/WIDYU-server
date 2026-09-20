package com.widyu.sensor.repository;

import com.widyu.sensor.SensorBatch;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SensorBatchRepository extends JpaRepository<SensorBatch, Long> {

    Optional<SensorBatch> findByBatchId(String batchId);
}
