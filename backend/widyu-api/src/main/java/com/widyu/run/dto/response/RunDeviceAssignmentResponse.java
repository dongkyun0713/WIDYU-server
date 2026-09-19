package com.widyu.run.dto.response;

import com.widyu.run.RunDeviceAssignment;

public record RunDeviceAssignmentResponse(
        String assignmentId,
        String deviceId,
        String role,
        String wearSite,
        Long assignedAtMs,
        Long unassignedAtMs
) {

    public static RunDeviceAssignmentResponse from(RunDeviceAssignment assignment) {
        return new RunDeviceAssignmentResponse(
                assignment.getAssignmentId(),
                assignment.getDeviceId(),
                assignment.getRole(),
                assignment.getWearSite(),
                assignment.getAssignedAtMs(),
                assignment.getUnassignedAtMs());
    }
}
