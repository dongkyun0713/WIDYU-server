package com.widyu.device.repository;

import com.widyu.device.DeviceHeartbeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DeviceHeartbeatRepository extends JpaRepository<DeviceHeartbeat, Long> {

    boolean existsByDeviceIdAndSessionIdAndTsMs(String deviceId, String sessionId, Long tsMs);

    /** 내보내기: 회차의 하트비트 원문을 잰 시각 순으로(LLD-0050 5.3). */
    java.util.List<DeviceHeartbeat> findByRunIdOrderByTsMsAsc(String runId);
}
