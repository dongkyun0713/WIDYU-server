package com.widyu.incident.dto.request;

import com.widyu.incident.IncidentOutcome;
import jakarta.validation.constraints.NotNull;

/** 보호자의 사후 판정(LLD-0054 3절). 119 신고 시각은 신고했을 때만 온다. */
public record IncidentResolveRequest(
        @NotNull IncidentOutcome outcome,
        Long emergencyCalledAtMs
) {}
