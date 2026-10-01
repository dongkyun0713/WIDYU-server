package com.widyu.incident.repository;

import com.widyu.incident.LabelAnnotation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LabelAnnotationRepository extends JpaRepository<LabelAnnotation, Long> {
    Optional<LabelAnnotation> findFirstByLabelIdOrderByRevisionDesc(Long labelId);
}
