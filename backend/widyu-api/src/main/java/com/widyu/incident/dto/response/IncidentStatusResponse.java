package com.widyu.incident.dto.response;

import com.widyu.incident.GuardianResponseType;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.IncidentState;
import java.util.List;

/** 알림 진입 시 조회하는 현재 상황. 시간 창 종료는 저장하지 않고 계산한다. */
public record IncidentStatusResponse(
        String incidentId,
        Status status,
        Long endedAtMs,
        EndedReason endedReason,
        IncidentKind kind,
        IncidentState state,
        Long lastDetectedAtMs,
        Long secondAlertSentAtMs,
        List<GuardianResponseType> myResponse
) {
    public enum Status { OPEN, ENDED }

    public enum EndedReason { SENIOR_OK, RESOLVED, WINDOW_ELAPSED }

    public static IncidentStatusResponse from(Incident incident, long nowMs, long windowMs,
            List<GuardianResponseType> myResponses) {
        long lastDetectedAtMs = incident.lastDetectedAtOrOpenedAt();
        Status status = Status.OPEN;
        Long endedAtMs = null;
        EndedReason endedReason = null;
        if (isEnded(incident, nowMs, windowMs)) {
            status = Status.ENDED;
            if (incident.getSituationEndedAtMs() != null) {
                endedAtMs = incident.getSituationEndedAtMs();
            } else if (incident.getState() == IncidentState.OK_CLOSED) {
                endedAtMs = firstPresent(incident.getRespondedAtMs(), incident.getOpenedAtMs());
            } else if (incident.getState() == IncidentState.RESOLVED) {
                endedAtMs = firstPresent(incident.getResolvedAtMs(), incident.getOpenedAtMs());
            } else {
                endedAtMs = lastDetectedAtMs + windowMs;
            }
            if (incident.getState() == IncidentState.OK_CLOSED) {
                endedReason = EndedReason.SENIOR_OK;
            } else if (incident.getState() == IncidentState.RESOLVED) {
                endedReason = EndedReason.RESOLVED;
            } else {
                endedReason = EndedReason.WINDOW_ELAPSED;
            }
        }
        return new IncidentStatusResponse(incident.getIncidentRef(), status, endedAtMs, endedReason,
                incident.getKind(), incident.getState(), lastDetectedAtMs,
                incident.getSecondAlertSentAtMs(), List.copyOf(myResponses));
    }

    public static boolean isEnded(Incident incident, long nowMs, long windowMs) {
        if (incident.getSituationEndedAtMs() != null) {
            return true;
        }
        if (incident.getState() == IncidentState.OK_CLOSED || incident.getState() == IncidentState.RESOLVED) {
            return true;
        }
        return nowMs >= incident.lastDetectedAtOrOpenedAt() + windowMs;
    }

    private static Long firstPresent(Long preferred, Long fallback) {
        if (preferred != null) {
            return preferred;
        }
        return fallback;
    }
}
