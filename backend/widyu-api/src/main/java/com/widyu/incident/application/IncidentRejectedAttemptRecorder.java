package com.widyu.incident.application;

import com.widyu.incident.IncidentResponseAttempt;
import com.widyu.incident.IncidentResponseValue;
import com.widyu.incident.ResponseAttemptOutcome;
import com.widyu.incident.ResponseVia;
import com.widyu.incident.repository.IncidentResponseAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 409 응답의 요청 트랜잭션이 롤백돼도 시도 이력은 커밋한다. */
@Service
@RequiredArgsConstructor
public class IncidentRejectedAttemptRecorder {
    private final IncidentResponseAttemptRepository attempts;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long incidentId, IncidentResponseValue response, ResponseVia via,
            Long deviceRespondedAtMs, long receivedAtMs, ResponseAttemptOutcome outcome) {
        attempts.save(IncidentResponseAttempt.of(incidentId, response, via,
                deviceRespondedAtMs, receivedAtMs, outcome));
    }
}
