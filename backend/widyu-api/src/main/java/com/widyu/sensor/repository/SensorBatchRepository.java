package com.widyu.sensor.repository;

import com.widyu.sensor.SensorBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SensorBatchRepository extends JpaRepository<SensorBatch, Long> {

    boolean existsByBatchId(String batchId);
}
