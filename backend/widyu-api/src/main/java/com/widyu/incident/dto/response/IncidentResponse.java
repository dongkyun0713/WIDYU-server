package com.widyu.incident.dto.response;

import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.IncidentOutcome;
import com.widyu.incident.IncidentResponseValue;
import com.widyu.incident.IncidentState;
import com.widyu.incident.ResponseVia;

/**
 * 사건 한 건의 응답(LLD-0054 3절).
 *
 * <p>심박 값·판정 사유·좌표는 싣지 않는다. 그건 판정 기록에 있고 연구·관리자 범위다(정책 1.5.5).
 */
public record IncidentResponse(
        String incidentId,
        String decisionId,
        String runId,
        IncidentKind kind,
        String level,
        Long openedAtMs,
        Long respondByMs,
        IncidentResponseValue response,
        Long respondedAtMs,
        ResponseVia responseVia,
        IncidentState state,
        IncidentOutcome outcome,
        Long resolvedBy,
        Long resolvedAtMs,
        Long emergencyCalledAtMs
) {

    public static IncidentResponse from(Incident incident) {
        return new IncidentResponse(
                incident.getIncidentRef(),
                incident.getDecisionId(),
                incident.getRunId(),
                incident.getKind(),
                incident.getLevel(),
                incident.getOpenedAtMs(),
                incident.getRespondByMs(),
                incident.getResponse(),
                incident.getRespondedAtMs(),
                incident.getResponseVia(),
                incident.getState(),
                incident.getOutcome(),
                incident.getResolvedBy(),
                incident.getResolvedAtMs(),
                incident.getEmergencyCalledAtMs());
    }
}
