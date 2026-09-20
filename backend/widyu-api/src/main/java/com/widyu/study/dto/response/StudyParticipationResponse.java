package com.widyu.study.dto.response;

import com.widyu.study.StudyParticipation;
import com.widyu.study.StudyParticipationStatus;
import com.widyu.study.WithdrawalScope;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/** 참여 기록 응답. 이름·전화번호 등 직접식별자는 싣지 않는다(LLD-0052 3절). */
public record StudyParticipationResponse(
        Long id,
        String studyId,
        String participationId,
        Long memberId,
        String consentVersion,
        LocalDate consentedAt,
        Map<String, Boolean> consents,
        String dataPolicy,
        LocalDate identifiedUntil,
        LocalDate pseudonymizedAt,
        LocalDate researchUntil,
        StudyParticipationStatus status,
        LocalDateTime withdrawnAt,
        WithdrawalScope withdrawalScope,
        Set<String> withdrawalConsentKeys,
        LocalDateTime deletionProcessedAt
) {

    public static StudyParticipationResponse from(StudyParticipation participation) {
        return new StudyParticipationResponse(
                participation.getId(),
                participation.getStudyId(),
                participation.getParticipationId(),
                participation.getMember().getId(),
                participation.getConsentVersion(),
                participation.getConsentedAt(),
                Map.copyOf(participation.getConsents()),
                participation.getDataPolicy(),
                participation.getIdentifiedUntil(),
                participation.getPseudonymizedAt(),
                participation.getResearchUntil(),
                participation.getStatus(),
                participation.getWithdrawnAt(),
                participation.getWithdrawalScope(),
                Set.copyOf(participation.getWithdrawalConsentKeys()),
                participation.getDeletionProcessedAt());
    }
}
