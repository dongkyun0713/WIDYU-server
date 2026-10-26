package com.widyu.mypage.event;

import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.dto.NotificationCopy;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FamilyLeaderChangedNotificationListener {

    private final FcmOutboxService outboxService;

    @EventListener
    public void onLeaderChanged(FamilyLeaderChangedEvent event) {
        NotificationType type = NotificationType.FAMILY_LEADER_CHANGED;
        NotificationCopy copy = NotificationCopy.of(type, "R01", Map.of());
        FcmSendDto message = FcmSendDto.builder()
                .title(copy.title())
                .content(copy.body())
                .fcmCategory(type.fcmCategory())
                .notificationType(type)
                .scheme(type.deepLinkTemplate())
                .deepLink(type.deepLinkTemplate())
                .build();
        outboxService.enqueue(event.newLeaderId(), message);
    }
}
