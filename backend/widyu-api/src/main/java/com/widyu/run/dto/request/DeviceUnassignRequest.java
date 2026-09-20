package com.widyu.run.dto.request;

public record DeviceUnassignRequest(Long unassignedAtMs) {

    public static DeviceUnassignRequest of(Long unassignedAtMs) {
        return new DeviceUnassignRequest(unassignedAtMs);
    }
}
