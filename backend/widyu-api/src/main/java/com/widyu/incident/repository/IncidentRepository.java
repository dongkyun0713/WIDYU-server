package com.widyu.incident.repository;

import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.IncidentOutcome;
import com.widyu.incident.IncidentResponseValue;
import com.widyu.incident.IncidentState;
import com.widyu.incident.ResponseVia;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface IncidentRepository extends JpaRepository<Incident, Long> {

    Optional<Incident> findByDecisionId(String decisionId);

    Optional<Incident> findByIncidentRef(String incidentRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Incident i WHERE i.id = :id")
    Optional<Incident> findByIdForUpdate(@Param("id") Long id);

    Optional<Incident> findFirstByMemberIdAndKindAndStateInAndSituationEndedAtMsIsNullOrderByOpenedAtMsDesc(
            Long memberId, IncidentKind kind, List<IncidentState> states);

    Optional<Incident> findFirstByMemberIdAndKindOrderByOpenedAtMsDesc(Long memberId, IncidentKind kind);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Incident i
               SET i.lastDecisionId = CASE WHEN :decisionId IS NULL THEN i.lastDecisionId ELSE :decisionId END,
                   i.lastDetectedAtMs = :detectedAtMs,
                   i.detectionCount = i.detectionCount + 1
             WHERE i.id = :id
               AND i.state IN (com.widyu.incident.IncidentState.CHECKING,
                               com.widyu.incident.IncidentState.ESCALATED)
               AND i.situationEndedAtMs IS NULL
               AND (:decisionId IS NULL OR
                    ((i.decisionId IS NULL OR i.decisionId <> :decisionId)
                     AND (i.lastDecisionId IS NULL OR i.lastDecisionId <> :decisionId)))
            """)
    int attachDetection(@Param("id") Long id, @Param("decisionId") String decisionId,
            @Param("detectedAtMs") long detectedAtMs);

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
                   i.deviceRespondedAtMs = :deviceRespondedAtMs,
                   i.responseVia = :responseVia,
                   i.state = CASE
                       WHEN :respondedAtMs >= i.respondByMs
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
            @Param("deviceRespondedAtMs") Long deviceRespondedAtMs,
            @Param("answeredState") IncidentState answeredState);

    /**
     * 보호자의 사후 판정을 한 문장으로 저장한다(LLD-0054 5.4).
     *
     * <p>읽고 고쳐 저장하면 보호자 둘이 같은 순간에 판정할 때 둘 다 종결 전 상태를 읽고 나중
     * 요청이 앞선 라벨과 판정자를 덮는다. 라벨은 사람이 쓴 사실이라 덮어쓰면 어느 쪽이 실제
     * 판단이었는지 남지 않는다. 조건을 같은 UPDATE에 두면 한 쪽만 성공한다.
     *
     * @return 1이면 저장, 0이면 이미 사후 판정이 끝난 사건이다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Incident i
               SET i.state = com.widyu.incident.IncidentState.RESOLVED,
                   i.outcome = :outcome,
                   i.resolvedBy = :resolvedBy,
                   i.resolvedAtMs = :resolvedAtMs,
                   i.emergencyCalledAtMs = :emergencyCalledAtMs
             WHERE i.incidentRef = :incidentRef
               AND i.state <> com.widyu.incident.IncidentState.RESOLVED
            """)
    int resolve(
            @Param("incidentRef") String incidentRef,
            @Param("outcome") IncidentOutcome outcome,
            @Param("resolvedBy") Long resolvedBy,
            @Param("resolvedAtMs") long resolvedAtMs,
            @Param("emergencyCalledAtMs") Long emergencyCalledAtMs);

    @Query("""
            SELECT i.id FROM Incident i
             WHERE i.id > :afterId
               AND i.kind = com.widyu.incident.IncidentKind.HR_ANOMALY
               AND ((i.state IN (com.widyu.incident.IncidentState.OPEN,
                                 com.widyu.incident.IncidentState.CHECKING)
                     AND i.respondByMs < :nowMs)
                    OR (i.state = com.widyu.incident.IncidentState.ESCALATED
                        AND i.initialAlertSentAtMs IS NULL))
             ORDER BY i.id
            """)
    List<Long> findDueIds(@Param("nowMs") long nowMs, @Param("afterId") long afterId, Pageable limit);

    /** 알림 발송 여부와 독립적으로 만료된 본인확인 상태를 올린다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Incident i
               SET i.state = com.widyu.incident.IncidentState.ESCALATED
             WHERE i.id = :id
               AND i.kind = com.widyu.incident.IncidentKind.HR_ANOMALY
               AND i.state IN (com.widyu.incident.IncidentState.OPEN,
                               com.widyu.incident.IncidentState.CHECKING)
               AND i.respondByMs < :nowMs
            """)
    int escalateTimedOutIfDue(@Param("id") Long id, @Param("nowMs") long nowMs);

    /** 이 게이트 UPDATE가 한 건을 바꾼 트랜잭션만 보호자 최초 알림을 enqueue한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Incident i
               SET i.initialAlertSentAtMs = :nowMs,
                   i.secondAlertDueAtMs = :nowMs + 180000
             WHERE i.id = :id
               AND i.initialAlertSentAtMs IS NULL
               AND i.kind = com.widyu.incident.IncidentKind.HR_ANOMALY
               AND i.state IN (com.widyu.incident.IncidentState.OPEN,
                               com.widyu.incident.IncidentState.CHECKING,
                               com.widyu.incident.IncidentState.ESCALATED)
               AND (i.respondByMs < :nowMs
                    OR i.state = com.widyu.incident.IncidentState.ESCALATED)
            """)
    int claimInitialAlertIfDue(@Param("id") Long id, @Param("nowMs") long nowMs);

    @Query("""
            SELECT i.id FROM Incident i
             WHERE i.id > :afterId
               AND i.kind = com.widyu.incident.IncidentKind.HR_ANOMALY
               AND i.secondAlertDueAtMs <= :nowMs
               AND i.secondAlertSentAtMs IS NULL
               AND i.secondAlertCancelledAtMs IS NULL
             ORDER BY i.id
            """)
    List<Long> findSecondAlertDueIds(@Param("nowMs") long nowMs, @Param("afterId") long afterId, Pageable limit);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Incident i SET i.secondAlertSentAtMs = :nowMs
             WHERE i.id = :id AND i.secondAlertSentAtMs IS NULL
               AND i.secondAlertCancelledAtMs IS NULL
               AND i.secondAlertDueAtMs <= :nowMs
               AND i.situationEndedAtMs IS NULL
            """)
    int claimSecondAlertIfDue(@Param("id") Long id, @Param("nowMs") long nowMs);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Incident i SET i.secondAlertCancelledAtMs = :nowMs
             WHERE i.id = :id AND i.secondAlertSentAtMs IS NULL
               AND i.secondAlertCancelledAtMs IS NULL
            """)
    int cancelSecondAlertIfPending(@Param("id") Long id, @Param("nowMs") long nowMs);

    /** 낙상 판정 경로는 이 PR에서 보호자 알림 순서를 바꾸지 않는다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Incident i
               SET i.state = com.widyu.incident.IncidentState.ESCALATED
             WHERE i.kind = com.widyu.incident.IncidentKind.FALL_SUSPECTED
               AND i.state IN (com.widyu.incident.IncidentState.OPEN,
                               com.widyu.incident.IncidentState.CHECKING)
               AND i.respondByMs < :nowMs
            """)
    int escalateFallTimedOut(@Param("nowMs") long nowMs);
}
