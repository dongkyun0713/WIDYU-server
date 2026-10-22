package com.widyu.heart.application;

import com.widyu.decision.DecisionRecord;
import com.widyu.decision.repository.DecisionRecordRepository;
import com.widyu.fcm.event.heart.dto.HeartRateEmergencyEvent;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.SensorProperties;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.application.IncidentEscalation;
import com.widyu.incident.application.IncidentService;
import com.widyu.member.repository.MemberRepository;
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
    private final MemberRepository memberRepository;

    /**
     * 회원 행 잠금을 이 트랜잭션의 첫 쿼리로 둔다(LLD-0103). 잠금 전에 판정 기록을 일반 SELECT로 읽으면 MySQL
     * REPEATABLE READ 스냅샷이 그 시점에 고정돼, 같은 시니어의 동시 위급이 잠금을 기다린 뒤에도 앞 트랜잭션이
     * 방금 연 사건을 보지 못하고 사건을 하나 더 연다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Timed("heart.emergency.notification")
    public void handleHeartRateEmergency(HeartRateEmergencyEvent event) {
        memberRepository.findByIdForUpdate(event.memberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
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
