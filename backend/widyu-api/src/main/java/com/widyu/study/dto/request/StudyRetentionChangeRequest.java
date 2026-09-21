package com.widyu.study.dto.request;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** IRB 보관 계획 수정(LLD-0052 3절). 아직 확정 전이면 전부 비워 둘 수 있다. */
public record StudyRetentionChangeRequest(
        @Size(max = 50) String dataPolicy,
        LocalDate identifiedUntil,
        LocalDate pseudonymizedAt,
        LocalDate researchUntil
) {
}
