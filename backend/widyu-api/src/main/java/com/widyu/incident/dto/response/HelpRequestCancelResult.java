package com.widyu.incident.dto.response;

import com.widyu.incident.Incident;
import com.widyu.incident.IncidentState;

public record HelpRequestCancelResult(String incidentId, IncidentState state, Long situationEndedAtMs) {
    public static HelpRequestCancelResult from(Incident incident) {
        return new HelpRequestCancelResult(incident.getIncidentRef(), incident.getState(),
                incident.getSituationEndedAtMs());
    }
}
