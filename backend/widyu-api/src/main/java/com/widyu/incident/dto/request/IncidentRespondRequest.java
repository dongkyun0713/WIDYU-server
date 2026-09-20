package com.widyu.incident.dto.request;

import com.widyu.incident.IncidentResponseValue;
import com.widyu.incident.ResponseVia;
import jakarta.validation.constraints.NotNull;

/** 시니어 본인의 확인 응답(LLD-0054 3절). */
public record IncidentRespondRequest(
        @NotNull IncidentResponseValue response,
        @NotNull ResponseVia via
) {}
