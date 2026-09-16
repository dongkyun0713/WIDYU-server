package com.widyu.study.repository;

import com.widyu.study.StudyParticipation;
import com.widyu.study.StudyParticipationStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudyParticipationRepository extends JpaRepository<StudyParticipation, Long> {

    Optional<StudyParticipation> findByParticipationId(String participationId);

    boolean existsByParticipationId(String participationId);

    boolean existsByMemberIdAndStatus(Long memberId, StudyParticipationStatus status);
}
