package com.widyu.decision.application;

import com.widyu.decision.DecisionRecord;
import com.widyu.decision.repository.DecisionRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 판정 외부 호출과 분리된 짧은 저장 트랜잭션. */
@Service
@RequiredArgsConstructor
public class DecisionRecordPersistenceService {

    private final DecisionRecordRepository decisionRecordRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DecisionRecord save(DecisionRecord record) {
        return decisionRecordRepository.save(record);
    }
}
