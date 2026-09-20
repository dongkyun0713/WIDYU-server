package com.widyu.incident.repository;

import com.widyu.incident.Incident;
import com.widyu.incident.IncidentState;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface IncidentRepository extends JpaRepository<Incident, Long> {

    Optional<Incident> findByDecisionId(String decisionId);

    Optional<Incident> findByIncidentRef(String incidentRef);

    List<Incident> findTop50ByMemberIdOrderByOpenedAtMsDesc(Long memberId);

    List<Incident> findTop50ByMemberIdAndStateOrderByOpenedAtMsDesc(Long memberId, IncidentState state);

    List<Incident> findTop50ByMemberIdAndResponseIsNullOrderByOpenedAtMsDesc(Long memberId);

    List<Incident> findByRunIdOrderByOpenedAtMsAsc(String runId);

    /**
     * 마감을 넘긴 미응답 사건을 한 문장으로 올린다(ADR-0035 결정 5).
     *
     * <p>행을 읽어 하나씩 고치면 폴링 주기마다 건수만큼 쿼리가 늘고, 두 워커가 같은 행을 잡으면
     * 상태가 엇갈린다. 조건이 상태에 들어 있어 이 UPDATE는 몇 번 돌아도 결과가 같다.
     */
    @Modifying
    @Query("""
            UPDATE Incident i
               SET i.state = com.widyu.incident.IncidentState.ESCALATED
             WHERE i.state IN (com.widyu.incident.IncidentState.OPEN,
                               com.widyu.incident.IncidentState.CHECKING)
               AND i.respondByMs < :nowMs
            """)
    int escalateTimedOut(@Param("nowMs") long nowMs);
}
