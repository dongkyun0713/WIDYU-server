package com.widyu.goal.medicineschedule.dto.response;

import java.time.LocalDate;

public record MedicineScheduleIdResponse(
        Long medicineScheduleId,
        long scheduleRevision,
        LocalDate effectiveFromDate
) {
    public static MedicineScheduleIdResponse of(Long medicineScheduleId, long scheduleRevision,
                                                LocalDate effectiveFromDate) {
        return new MedicineScheduleIdResponse(medicineScheduleId, scheduleRevision, effectiveFromDate);
    }
}
