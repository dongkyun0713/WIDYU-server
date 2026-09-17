package com.widyu.goal.medicineschedule.dto.response;

import com.widyu.medicine.MedicineSchedule;
import java.util.List;

public record MedicationAlarmSyncResponse(
        long revision,
        String timeZone,
        List<Schedule> schedules,
        List<String> completed
) {
    public static MedicationAlarmSyncResponse of(
            long revision, String timeZone, List<MedicineSchedule> schedules, List<String> completed) {
        return new MedicationAlarmSyncResponse(revision, timeZone,
                schedules.stream().map(Schedule::from).toList(), completed);
    }

    public record Schedule(Long scheduleId, String alarmTime, Integer doseCount) {
        static Schedule from(MedicineSchedule schedule) {
            return new Schedule(schedule.getId(), schedule.getAlarmTime().toString(), schedule.getTotalCount());
        }
    }
}
