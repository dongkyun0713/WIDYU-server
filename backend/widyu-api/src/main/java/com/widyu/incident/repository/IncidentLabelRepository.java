package com.widyu.incident.repository;

import com.widyu.incident.IncidentLabel;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentLabelRepository extends JpaRepository<IncidentLabel, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM IncidentLabel l WHERE l.incidentRef = :incidentRef")
    Optional<IncidentLabel> findByIncidentRefForUpdate(@Param("incidentRef") String incidentRef);
}
