package com.widyu.study.repository;

import com.widyu.study.StudyParticipation;
import com.widyu.study.StudyParticipationStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudyParticipationRepository extends JpaRepository<StudyParticipation, Long> {

    Optional<StudyParticipation> findByParticipationId(String participationId);

    /** 같은 연구·회원의 ACTIVE 참여는 하나뿐이다(LLD-0052 5절). */
    boolean existsByStudyIdAndMemberIdAndStatus(
            String studyId, Long memberId, StudyParticipationStatus status);
}
