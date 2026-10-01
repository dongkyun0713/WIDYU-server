package com.widyu.incident.dto.response;

import com.widyu.incident.GuardianResponseType;
import com.widyu.incident.Incident;

public record GuardianResponseResult(
        String incidentId,
        GuardianResponseType guardianResponseType,
        Long guardianResponseAtMs,
        Long guardianResponseBy
) {
    public static GuardianResponseResult from(Incident incident) {
        return new GuardianResponseResult(incident.getIncidentRef(), incident.getGuardianResponseType(),
                incident.getGuardianResponseAtMs(), incident.getGuardianResponseBy());
    }
}
