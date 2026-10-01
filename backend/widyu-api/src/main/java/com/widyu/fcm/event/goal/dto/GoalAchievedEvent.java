package com.widyu.fcm.event.goal.dto;

public record GoalAchievedEvent(Long seniorId, String goalName, long points, String entityId, String eventId) {

    public static GoalAchievedEvent forWalk(Long seniorId, Long walkId, long points) {
        return new GoalAchievedEvent(seniorId, "걷기", points, walkId.toString(), "G01:W:" + walkId);
    }

    public static GoalAchievedEvent forHealthSchedule(Long seniorId, Long scheduleId, long points) {
        return new GoalAchievedEvent(seniorId, "건강 일정", points, scheduleId.toString(), "G01:H:" + scheduleId);
    }

    public static GoalAchievedEvent forMedicationDay(Long seniorId, Long proofId, long points) {
        return new GoalAchievedEvent(seniorId, "하루 복약", points, proofId.toString(), "G01:M:" + proofId);
    }
}
