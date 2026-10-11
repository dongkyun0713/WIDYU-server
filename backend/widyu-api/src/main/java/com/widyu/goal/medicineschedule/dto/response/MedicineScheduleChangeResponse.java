package com.widyu.goal.medicineschedule.dto.response;

import java.time.LocalDate;

public record MedicineScheduleChangeResponse(
        long scheduleRevision,
        LocalDate effectiveFromDate
) {
    public static MedicineScheduleChangeResponse of(long scheduleRevision, LocalDate effectiveFromDate) {
        return new MedicineScheduleChangeResponse(scheduleRevision, effectiveFromDate);
    }
}
