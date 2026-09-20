package com.widyu.study.dto.request;

import com.widyu.study.WithdrawalScope;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

/** 연구 동의 철회 요청. 실제 자료 삭제는 수행하지 않는다. */
public record StudyWithdrawalRequest(
        @NotNull WithdrawalScope scope,
        Set<@Size(max = 64) String> consentKeys
) {}
