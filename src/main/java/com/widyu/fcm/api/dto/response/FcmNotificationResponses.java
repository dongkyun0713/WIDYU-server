package com.widyu.fcm.api.dto.response;

import com.widyu.fcm.domain.FcmNotification;
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
