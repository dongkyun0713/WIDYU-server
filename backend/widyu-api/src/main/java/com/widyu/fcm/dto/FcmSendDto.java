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
        Map<String, String> data
) {
    public FcmSendDto {
        if (data == null) {
            data = Map.of();
        }
    }

    public FcmSendDto(String title, String content, FcmCategory category, String scheme, String image) {
        this(title, content, category, scheme, image, false, null, Map.of());
    }

    public FcmSendDto withRelatedMember(Long memberId) {
        return new FcmSendDto(title, content, fcmCategory, scheme, image, emergency, memberId, data);
    }
}
