package com.widyu.study.dto.request;

import com.widyu.study.DataPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** 정책서 1.5.3 필수 필드. 하나라도 없으면 등록(수집 시작)을 거부한다. */
public record StudyParticipationCreateRequest(
        @NotBlank(message = "studyId는 필수입니다.") @Size(max = 50) String studyId,
        @NotBlank(message = "participationId는 필수입니다.") @Size(max = 50) String participationId,
        @NotNull(message = "memberId는 필수입니다.") Long memberId,
        @NotNull(message = "dataPolicy는 필수입니다.") DataPolicy dataPolicy,
        @NotNull(message = "identifiedUntil은 필수입니다.") LocalDate identifiedUntil,
        @NotNull(message = "pseudonymizedAt은 필수입니다.") LocalDate pseudonymizedAt,
        @NotNull(message = "researchUntil은 필수입니다.") LocalDate researchUntil,
        @NotBlank(message = "consentVersion은 필수입니다.") @Size(max = 50) String consentVersion
) {
}
