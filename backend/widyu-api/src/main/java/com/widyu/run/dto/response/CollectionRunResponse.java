package com.widyu.run.dto.response;

import com.widyu.run.CollectionRun;
import com.widyu.run.CollectionRunStatus;
import com.widyu.run.RunDeviceAssignment;
import com.widyu.run.RunMarker;
import java.time.LocalDate;
import java.util.List;

public record CollectionRunResponse(
        String runId,
        Long subjectMemberId,
        String studyId,
        String participationId,
        String protocolRef,
        String consentVersion,
        String collectionMode,
        Long startedAtMs,
        Long endedAtMs,
        CollectionRunStatus status,
        RetentionResponse retention,
        String qualityNotes,
        String missingReason,
        List<RunDeviceAssignmentResponse> devices,
        List<RunMarkerResponse> markers
) {

    public record RetentionResponse(
            String dataPolicy,
            LocalDate identifiedUntil,
            LocalDate pseudonymizedAt,
            LocalDate researchUntil
    ) {}

    public static CollectionRunResponse from(
            CollectionRun run, List<RunDeviceAssignment> assignments, List<RunMarker> markers) {
        return new CollectionRunResponse(
                run.getRunId(),
                run.getMember().getId(),
                run.getStudyId(),
                run.getParticipationId(),
                run.getProtocolRef(),
                run.getConsentVersion(),
                run.getCollectionMode(),
                run.getStartedAtMs(),
                run.getEndedAtMs(),
                run.getStatus(),
                new RetentionResponse(
                        run.getDataPolicy(),
                        run.getIdentifiedUntil(),
                        run.getPseudonymizedAt(),
                        run.getResearchUntil()),
                run.getQualityNotes(),
                run.getMissingReason(),
                assignments.stream().map(RunDeviceAssignmentResponse::from).toList(),
                markers.stream().map(RunMarkerResponse::from).toList());
    }
}
