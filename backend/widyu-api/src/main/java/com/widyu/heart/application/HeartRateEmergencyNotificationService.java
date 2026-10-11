package com.widyu.heart.application;

import com.widyu.decision.DecisionRecord;
import com.widyu.decision.repository.DecisionRecordRepository;
import com.widyu.fcm.event.heart.dto.HeartRateEmergencyEvent;
import com.widyu.global.properties.SensorProperties;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.application.IncidentEscalation;
import com.widyu.incident.application.IncidentService;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 심박 저장 커밋 뒤 사건을 열고 보호자 최초 알림을 사건에서만 발행한다. */
@Service
@RequiredArgsConstructor
public class HeartRateEmergencyNotificationService {

    private final DecisionRecordRepository decisionRecordRepository;
    private final IncidentService incidentService;
    private final IncidentEscalation incidentEscalation;
    private final SensorProperties sensorProperties;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Timed("heart.emergency.notification")
    public void handleHeartRateEmergency(HeartRateEmergencyEvent event) {
        Incident incident;
        if (event.decisionId() == null) {
            incident = incidentService.openForAlert(event.memberId(), IncidentKind.HR_ANOMALY);
        } else {
            DecisionRecord decision = decisionRecordRepository.findByDecisionId(event.decisionId())
                    .orElseThrow(() -> new IllegalStateException("위급 판정 기록을 찾을 수 없습니다."));
            incident = incidentService.openForAlert(decision, IncidentKind.HR_ANOMALY);
        }
        if (sensorProperties.incident().selfCheckFirst() || incident.getInitialAlertSentAtMs() != null) {
            return;
        }
        incidentEscalation.sendImmediately(incident, System.currentTimeMillis());
    }
}
