package com.widyu.decision.repository;

import com.widyu.decision.DecisionRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface DecisionRecordRepository extends JpaRepository<DecisionRecord, Long> {

    List<DecisionRecord> findByRunIdOrderByDecisionAtMsAsc(String runId);

    Optional<DecisionRecord> findByDecisionId(String decisionId);

    /**
     * 알림 도달 사실을 <b>첫 성공 한 번만</b> 적는다(ADR-0035 결정 3).
     *
     * <p>보호자가 여럿이면 각자의 outbox 완료 트랜잭션이 동시에 들어온다. 읽어서 비었는지 보고 쓰는
     * 방식이면 두 트랜잭션이 같은 빈 값을 읽고 나중 것이 앞선 시각을 덮어쓴다. 그래서 「비어 있을
     * 때만」이라는 조건을 UPDATE 문 안에 넣어 DB가 한 번만 성공시키게 한다.
     *
     * @return 이번 호출이 첫 성공이면 1, 이미 기록돼 있으면 0
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update DecisionRecord d set d.alertDelivered = true, d.alertId = :alertId, "
            + "d.alertAtMs = :alertAtMs where d.decisionId = :decisionId and d.alertId is null")
    int markDeliveredIfFirst(
            @Param("decisionId") String decisionId,
            @Param("alertId") String alertId,
            @Param("alertAtMs") Long alertAtMs);
}
