package com.widyu.fcm.event.safezone.listener;

import com.widyu.fcm.event.safezone.dto.SafeZoneExitEvent;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.application.IncidentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 위치 갱신 커밋 뒤 안심구역 사건을 열어 S02와 조건부 S05를 사건 경로로 발송한다. */
@Component
@RequiredArgsConstructor
public class SafeZoneNotificationListener {

    private final IncidentService incidentService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleSafeZoneExit(SafeZoneExitEvent event) {
        incidentService.openForAlert(event.seniorMemberId(), IncidentKind.SAFE_ZONE_EXIT);
    }
}
