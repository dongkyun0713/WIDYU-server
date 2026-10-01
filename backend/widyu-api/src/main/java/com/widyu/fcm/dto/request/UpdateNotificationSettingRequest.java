package com.widyu.fcm.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateNotificationSettingRequest(
        @NotNull(message = "그룹은 필수입니다.")
        String group,

        @NotNull(message = "활성화 여부는 필수입니다.")
        Boolean enabled,

        @NotNull(message = "정책 revision은 필수입니다.")
        @PositiveOrZero(message = "정책 revision은 0 이상이어야 합니다.")
        Long policyRevision
) {
}
