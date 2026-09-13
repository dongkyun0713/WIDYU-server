package com.widyu.goal.medicineschedule.dto.response;

import java.util.List;

public record MedicineSearchResponse(
        List<MedicineItem> medicines
) {
    public static MedicineSearchResponse of(List<MedicineItem> medicines) {
        return new MedicineSearchResponse(medicines);
    }

    public static MedicineSearchResponse empty() {
        return of(List.of());
    }

    public record MedicineItem(
            Long medicineId,
            String itemName,
            String itemImage,
            String usage,
            String efficacy

    ) {}
}
