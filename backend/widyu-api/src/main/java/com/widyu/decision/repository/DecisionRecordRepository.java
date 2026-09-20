package com.widyu.decision.repository;

import com.widyu.decision.DecisionRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DecisionRecordRepository extends JpaRepository<DecisionRecord, Long> {

    List<DecisionRecord> findByRunIdOrderByDecisionAtMsAsc(String runId);
}
