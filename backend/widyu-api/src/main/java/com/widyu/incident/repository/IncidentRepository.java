package com.widyu.incident.repository;

import com.widyu.incident.Incident;
import com.widyu.incident.IncidentResponseValue;
import com.widyu.incident.IncidentState;
import com.widyu.incident.ResponseVia;
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
     * 본인 응답을 한 문장으로 저장한다(LLD-0054 5.2).
     *
     * <p>읽고 고쳐 저장하면 그 사이에 무응답 스케줄러가 올린 {@code ESCALATED}를 응답 트랜잭션이
     * 덮는다. 조건과 상태 결정을 같은 UPDATE에 두면 그 틈이 없다.
     *
     * <p>마감을 넘겼는지도 행을 보고 정한다. 마감이 지난 뒤 스케줄러가 돌기 전(최대 폴링 주기)에
     * 온 {@code OK}를 {@code OK_CLOSED}로 적으면, 무응답이던 사건이 정상 종료로 둔갑하고 그 뒤
     * 스케줄러 대상에서도 빠진다.
     *
     * @return 1이면 저장, 0이면 이미 답했거나 종결됐거나 본인 사건이 아니다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Incident i
               SET i.response = :response,
                   i.respondedAtMs = :respondedAtMs,
                   i.responseVia = :responseVia,
                   i.state = CASE
                       WHEN :respondedAtMs > i.respondByMs
                           THEN com.widyu.incident.IncidentState.ESCALATED
                       WHEN i.state = com.widyu.incident.IncidentState.ESCALATED
                           THEN com.widyu.incident.IncidentState.ESCALATED
                       ELSE :answeredState
                   END
             WHERE i.incidentRef = :incidentRef
               AND i.memberId = :memberId
               AND i.response IS NULL
               AND i.state <> com.widyu.incident.IncidentState.RESOLVED
            """)
    int respond(
            @Param("incidentRef") String incidentRef,
            @Param("memberId") Long memberId,
            @Param("response") IncidentResponseValue response,
            @Param("responseVia") ResponseVia responseVia,
            @Param("respondedAtMs") long respondedAtMs,
            @Param("answeredState") IncidentState answeredState);

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
