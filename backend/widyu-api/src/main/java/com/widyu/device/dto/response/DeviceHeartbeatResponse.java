package com.widyu.device.dto.response;

public record DeviceHeartbeatResponse(Long tsMs, DeviceHeartbeatResult result) {

    public static DeviceHeartbeatResponse of(Long tsMs, DeviceHeartbeatResult result) {
        return new DeviceHeartbeatResponse(tsMs, result);
    }
}
