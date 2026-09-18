package com.widyu.sensor.repository;

import com.widyu.sensor.SensorBatch;
import com.widyu.sensor.SensorStreamType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SensorBatchRepository extends JpaRepository<SensorBatch, Long> {

    boolean existsByMemberIdAndDeviceIdAndSessionIdAndStreamTypeAndSeq(
            Long memberId, String deviceId, String sessionId, SensorStreamType streamType, Long seq);
}
