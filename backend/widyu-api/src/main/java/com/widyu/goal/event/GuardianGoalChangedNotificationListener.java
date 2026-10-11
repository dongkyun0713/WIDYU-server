package com.widyu.goal.event;

import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.dto.NotificationCopy;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GuardianGoalChangedNotificationListener {

    private final FcmOutboxService outboxService;

    @EventListener
    public void onGuardianGoalChanged(GuardianGoalChangedEvent event) {
        NotificationType type = event.type();
        String actorName = event.actorDisplayName();
        if (actorName == null || actorName.isBlank()) {
            actorName = "가족";
        }

        Map<String, String> values = new HashMap<>();
        values.put("보호자 이름", actorName);
        if (event.goalSteps() != null) {
            values.put("목표 걸음 수", event.goalSteps().toString());
        }
        NotificationCopy copy = NotificationCopy.of(type, type.copyCode(), values);
        FcmSendDto message = FcmSendDto.builder()
                .title(copy.title())
                .content(copy.body())
                .notificationType(type)
                .entityId(event.entityId())
                .actorDisplayName(actorName)
                .build();
        outboxService.enqueue(event.seniorId(), message);
    }
}
