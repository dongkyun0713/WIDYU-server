package com.widyu.incident.repository;

import com.widyu.incident.IncidentResponseAttempt;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentResponseAttemptRepository extends JpaRepository<IncidentResponseAttempt, Long> {
    List<IncidentResponseAttempt> findByIncidentIdOrderByIdAsc(Long incidentId);
}
