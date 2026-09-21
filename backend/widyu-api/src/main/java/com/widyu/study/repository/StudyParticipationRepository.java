package com.widyu.study.repository;

import com.widyu.study.StudyParticipation;
import com.widyu.study.StudyParticipationStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface StudyParticipationRepository extends JpaRepository<StudyParticipation, Long> {

    Optional<StudyParticipation> findByParticipationId(String participationId);

    /** 회차 개설과 철회가 겹치지 않게 참여 기록을 잠그고 읽는다(LLD-0052 5절). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM StudyParticipation p WHERE p.participationId = :participationId")
    Optional<StudyParticipation> findByParticipationIdForUpdate(String participationId);

    /** 같은 연구·회원의 ACTIVE 참여는 하나뿐이다(LLD-0052 5절). */
    boolean existsByStudyIdAndMemberIdAndStatus(
            String studyId, Long memberId, StudyParticipationStatus status);
}
