package com.widyu.fcm.application;

import com.widyu.fcm.FcmOutbox;
import com.widyu.fcm.dto.FcmSendDto;

public record FcmDelivery(Long id, long fence, String token, FcmSendDto message) {
    public static FcmDelivery from(FcmOutbox row) {
        return new FcmDelivery(row.getId(), row.getFence(), row.getMemberFcmToken().getToken(),
                new FcmSendDto(row.getTitle(), row.getBody(), row.getFcmCategory(), row.getScheme(),
                        row.getImage(), row.isEmergency(), row.getRelatedMemberId()));
    }
}
