package com.widyu.fcm.dto;

import com.widyu.fcm.FcmCategory;
import lombok.Builder;
import java.util.Map;

@Builder
public record FcmSendDto(
        String title,
        String content,
        FcmCategory fcmCategory,
        String scheme,
        String image,
        boolean emergency,
        Long relatedMemberId,
        Map<String, String> data,
        /** 이 알림을 낳은 판정 기록. 전송이 성공하면 그 행에 도달 사실을 채운다(LLD-0053 5.2). 대개 null이다. */
        String decisionId
) {
    public FcmSendDto {
        if (data == null) {
            data = Map.of();
        }
    }

    public FcmSendDto(String title, String content, FcmCategory category, String scheme, String image) {
        this(title, content, category, scheme, image, false, null, Map.of(), null);
    }

    public FcmSendDto withRelatedMember(Long memberId) {
        return new FcmSendDto(title, content, fcmCategory, scheme, image, emergency, memberId, data, decisionId);
    }
}
