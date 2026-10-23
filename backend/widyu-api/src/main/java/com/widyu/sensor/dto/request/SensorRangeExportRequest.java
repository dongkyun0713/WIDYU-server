package com.widyu.sensor.dto.request;

import jakarta.validation.constraints.NotNull;

public record SensorRangeExportRequest(
        @NotNull Long startAtMs,
        @NotNull Long endAtMs
) {}
