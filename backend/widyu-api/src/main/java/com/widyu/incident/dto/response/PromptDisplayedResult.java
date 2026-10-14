package com.widyu.incident.dto.response;

import com.widyu.incident.Incident;
import com.widyu.incident.IncidentState;

/** 앱은 {@code respondByMs}로 남은 시간을 그린다. {@code deadlineExtended}는 이번 요청이 마감을 옮겼는지다. */
public record PromptDisplayedResult(
        String incidentId,
        IncidentState state,
        Long promptDisplayedAtMs,
        Long respondByMs,
        boolean deadlineExtended
) {
    public static PromptDisplayedResult of(Incident incident, boolean deadlineExtended) {
        return new PromptDisplayedResult(incident.getIncidentRef(), incident.getState(),
                incident.getPromptDisplayedAtMs(), incident.getRespondByMs(), deadlineExtended);
    }
}
