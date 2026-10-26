package com.widyu.goal.event;

import com.widyu.fcm.NotificationPolicy;

public record GuardianGoalChangedEvent(
        Long seniorId,
        NotificationPolicy type,
        String entityId,
        Integer goalSteps,
        String actorDisplayName
) {
    public static GuardianGoalChangedEvent healthSchedule(
            Long seniorId, NotificationPolicy type, Long scheduleId, String actorDisplayName
    ) {
        return new GuardianGoalChangedEvent(seniorId, type, scheduleId.toString(), null, actorDisplayName);
    }

    public static GuardianGoalChangedEvent walkGoal(Long seniorId, Integer goalSteps, String actorDisplayName) {
        return new GuardianGoalChangedEvent(
                seniorId, NotificationPolicy.walk("W02"), null, goalSteps, actorDisplayName);
    }
}
