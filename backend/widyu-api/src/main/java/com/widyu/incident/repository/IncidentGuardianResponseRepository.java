package com.widyu.incident.repository;

import com.widyu.incident.GuardianResponseType;
import com.widyu.incident.IncidentGuardianResponse;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentGuardianResponseRepository extends JpaRepository<IncidentGuardianResponse, Long> {
    boolean existsByIncidentId(Long incidentId);

    // 사건 잠금 뒤에도 MySQL REPEATABLE READ의 선행 일반 SELECT 스냅샷을 사용하지 않는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<IncidentGuardianResponse> findByIncidentIdAndGuardianMemberIdAndResponseType(
            Long incidentId, Long guardianMemberId, GuardianResponseType responseType);
}
