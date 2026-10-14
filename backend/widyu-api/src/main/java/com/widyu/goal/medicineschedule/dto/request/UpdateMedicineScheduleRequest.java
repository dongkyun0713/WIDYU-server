package com.widyu.goal.medicineschedule.dto.request;

import com.widyu.goal.medicineschedule.validator.TimeFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdateMedicineScheduleRequest(
        @NotBlank(message = "알람 시간은 필수입니다.")
        @TimeFormat
        String alarmTime,

        @NotNull(message = "카테고리는 필수입니다.")
        @NotEmpty(message = "최소 하나의 카테고리가 필요합니다.")
        @Valid
        List<CategoryItem> categories
) {
    public record CategoryItem(
            @NotBlank(message = "카테고리 이름은 필수입니다.")
            @Size(max = 100, message = "카테고리 이름은 100자 이하로 입력해 주세요.")
            String name,

            @Valid
            List<MedicineItem> medicines
    ) {}

    public record MedicineItem(
            @NotBlank(message = "약품 이름은 필수입니다.")
            String itemName,

            @NotNull(message = "복용량은 필수입니다.")
            @Positive(message = "복용량은 양수여야 합니다.")
            Double dose,

            String itemImage,

            String usage,

            String efficacy
    ) {}
}
