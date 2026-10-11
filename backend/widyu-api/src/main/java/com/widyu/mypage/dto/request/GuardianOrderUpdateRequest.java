package com.widyu.mypage.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record GuardianOrderUpdateRequest(
        @NotEmpty List<@NotNull Long> guardianIds
) {}
