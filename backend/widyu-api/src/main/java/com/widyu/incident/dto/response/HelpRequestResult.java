package com.widyu.incident.dto.response;

import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.IncidentState;

public record HelpRequestResult(String incidentId, IncidentKind kind, IncidentState state, boolean attached) {
    public static HelpRequestResult of(Incident incident, boolean attached) {
        return new HelpRequestResult(incident.getIncidentRef(), incident.getKind(), incident.getState(), attached);
    }
}
