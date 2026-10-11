package com.widyu.incident.dto.request;

import com.widyu.incident.GuardianResponseType;
import jakarta.validation.constraints.NotNull;

public record GuardianResponseRequest(@NotNull GuardianResponseType type) {
}
