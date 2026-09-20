package com.widyu.location.raw.repository;

import com.widyu.location.raw.LocationFix;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LocationFixRepository extends JpaRepository<LocationFix, Long> {

    boolean existsByDeviceIdAndSessionIdAndSeq(String deviceId, String sessionId, Long seq);
}
