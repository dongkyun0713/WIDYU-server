package com.widyu.domain.fcm.api.dto.response;

import com.widyu.domain.fcm.api.dto.FcmSendDto;

public record FcmSendResponse(
        String title,
        String body,
        int successCount
) {
    public static FcmSendResponse of(FcmSendDto fcmSendDto, int successCount) {
        return new FcmSendResponse(fcmSendDto.title(), fcmSendDto.body(), successCount);
    }
}

