package com.widyu.domain.fcm.api.dto.response;

import com.widyu.domain.fcm.domain.FcmNotification;
import java.util.List;

public record FcmNotificationResponses(
        List<FcmNotificationResponse> notifications
) {
    public static FcmNotificationResponses from(List<FcmNotification> list) {
        return new FcmNotificationResponses(
                list.stream().map(FcmNotificationResponse::from).toList()
        );
    }
}
