package com.widyu.goal.event;

import com.widyu.fcm.NotificationType;

public record GuardianGoalChangedEvent(
        Long seniorId,
        NotificationType type,
        String entityId,
        Integer goalSteps,
        String actorDisplayName
) {
    public static GuardianGoalChangedEvent healthSchedule(
            Long seniorId, NotificationType type, Long scheduleId, String actorDisplayName
    ) {
        return new GuardianGoalChangedEvent(seniorId, type, scheduleId.toString(), null, actorDisplayName);
    }

    public static GuardianGoalChangedEvent walkGoal(Long seniorId, Integer goalSteps, String actorDisplayName) {
        return new GuardianGoalChangedEvent(
                seniorId, NotificationType.WALK_GOAL_CHANGED, null, goalSteps, actorDisplayName);
    }
}
