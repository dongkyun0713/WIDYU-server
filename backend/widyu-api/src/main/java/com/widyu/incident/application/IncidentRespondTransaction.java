package com.widyu.incident.application;

import com.widyu.incident.dto.request.IncidentRespondRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 응답 조건부 UPDATE와 수락 시도 이력을 커밋한 뒤 409 이력 저장으로 넘어간다. */
@Service
@RequiredArgsConstructor
public class IncidentRespondTransaction {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IncidentService.RespondResult execute(IncidentService service, Long memberId, String incidentRef,
            IncidentRespondRequest request) {
        return service.applyRespond(memberId, incidentRef, request);
    }
}
