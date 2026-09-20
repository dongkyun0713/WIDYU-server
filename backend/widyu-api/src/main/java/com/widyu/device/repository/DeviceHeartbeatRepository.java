package com.widyu.device.repository;

import com.widyu.device.DeviceHeartbeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DeviceHeartbeatRepository extends JpaRepository<DeviceHeartbeat, Long> {

    boolean existsByDeviceIdAndSessionIdAndTsMs(String deviceId, String sessionId, Long tsMs);
}
