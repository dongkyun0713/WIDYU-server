package com.widyu.study.dto.response;

import com.widyu.study.DataPolicy;
import com.widyu.study.StudyParticipation;
import com.widyu.study.StudyParticipationStatus;
import java.time.LocalDate;

public record StudyParticipationResponse(
        Long id,
        String studyId,
        String participationId,
        Long memberId,
        DataPolicy dataPolicy,
        LocalDate identifiedUntil,
        LocalDate pseudonymizedAt,
        LocalDate researchUntil,
        String consentVersion,
        StudyParticipationStatus status
) {

    public static StudyParticipationResponse from(StudyParticipation participation) {
        Long memberId = null;
        if (participation.getMember() != null) {
            memberId = participation.getMember().getId();
        }
        return new StudyParticipationResponse(
                participation.getId(),
                participation.getStudyId(),
                participation.getParticipationId(),
                memberId,
                participation.getDataPolicy(),
                participation.getIdentifiedUntil(),
                participation.getPseudonymizedAt(),
                participation.getResearchUntil(),
                participation.getConsentVersion(),
                participation.getStatus()
        );
    }
}
