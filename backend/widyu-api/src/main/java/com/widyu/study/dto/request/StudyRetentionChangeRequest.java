package com.widyu.study.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** 보존 기간 변경. IRB 승인 참조와 동의 버전을 함께 받아 이력에 남긴다(정책서 1.5.3). */
public record StudyRetentionChangeRequest(
        @NotNull(message = "identifiedUntil은 필수입니다.") LocalDate identifiedUntil,
        @NotNull(message = "pseudonymizedAt은 필수입니다.") LocalDate pseudonymizedAt,
        @NotNull(message = "researchUntil은 필수입니다.") LocalDate researchUntil,
        @NotBlank(message = "consentVersion은 필수입니다.") @Size(max = 50) String consentVersion,
        @NotBlank(message = "irbApprovalRef는 필수입니다.") @Size(max = 100) String irbApprovalRef
) {
}
