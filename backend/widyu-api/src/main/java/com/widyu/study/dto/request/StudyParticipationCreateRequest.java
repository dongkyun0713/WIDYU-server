package com.widyu.study.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Map;

/**
 * 관리자가 서면 연구동의를 받아 등록하는 실증 참여(LLD-0052 3절).
 * 참여 식별자는 서버가 발급하므로 받지 않고, 보관 계획은 IRB 승인 전이면 비울 수 있다.
 */
public record StudyParticipationCreateRequest(
        @NotBlank(message = "studyId는 필수입니다.") @Size(max = 50) String studyId,
        @NotNull(message = "memberId는 필수입니다.") Long memberId,
        @NotBlank(message = "consentVersion은 필수입니다.") @Size(max = 50) String consentVersion,
        @NotNull(message = "consentedAt은 필수입니다.") LocalDate consentedAt,
        Map<@Size(max = 64) String, Boolean> consents,
        @Size(max = 50) String dataPolicy,
        LocalDate identifiedUntil,
        LocalDate pseudonymizedAt,
        LocalDate researchUntil
) {
}
