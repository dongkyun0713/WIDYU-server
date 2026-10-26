package com.widyu.mypage.event;

import com.widyu.fcm.NotificationPolicy;
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
        NotificationPolicy type = NotificationPolicy.familyLeader();
        NotificationCopy copy = NotificationCopy.of(type, "R01", Map.of());
        FcmSendDto message = FcmSendDto.builder()
                .title(copy.title())
                .content(copy.body())
                .fcmCategory(type.fcmCategory())
                .notificationPolicy(type)
                .scheme(type.deepLinkTemplate())
                .deepLink(type.deepLinkTemplate())
                .build();
        outboxService.enqueue(event.newLeaderId(), message);
    }
}
