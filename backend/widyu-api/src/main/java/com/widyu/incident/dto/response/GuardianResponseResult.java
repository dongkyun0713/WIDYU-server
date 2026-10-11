package com.widyu.incident.dto.response;

import com.widyu.incident.GuardianResponseType;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentGuardianResponse;

public record GuardianResponseResult(
        String incidentId,
        GuardianResponseType guardianResponseType,
        Long guardianResponseAtMs,
        Long guardianResponseBy,
        boolean secondAlertCancelled,
        boolean incidentEnded
) {
    public static GuardianResponseResult of(Incident incident, IncidentGuardianResponse response,
            boolean secondAlertCancelled, boolean incidentEnded) {
        return new GuardianResponseResult(incident.getIncidentRef(), response.getResponseType(),
                response.getRespondedAtMs(), response.getGuardianMemberId(), secondAlertCancelled, incidentEnded);
    }
}
