package com.widyu.run.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 기기 배정. {@code wearSite} 값 집합은 합의 대기 S1이라 문자열로 받는다. */
public record DeviceAssignRequest(
        @NotNull(message = "기기 ID는 필수입니다.")
        @Pattern(regexp = "^[a-z0-9._-]{1,64}$", message = "기기 ID는 소문자·숫자·._- 1~64자여야 합니다.")
        String deviceId,

        @NotNull(message = "기기 역할은 필수입니다.")
        @Pattern(regexp = "watch|phone|external_ecg|operator_marker",
                message = "기기 역할은 watch, phone, external_ecg, operator_marker 중 하나여야 합니다.")
        String role,

        @Size(max = 30, message = "착용 위치는 30자 이하여야 합니다.")
        String wearSite,

        Long assignedAtMs
) {

    public static DeviceAssignRequest of(
            String deviceId, String role, String wearSite, Long assignedAtMs) {
        return new DeviceAssignRequest(deviceId, role, wearSite, assignedAtMs);
    }
}
