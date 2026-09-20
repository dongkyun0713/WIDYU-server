package com.widyu.fcm.dto.response;

import com.widyu.fcm.FcmCategory;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class FcmCategoryResponse {
    private String label;
    private String name;
    private long count;

    public static FcmCategoryResponse of(FcmCategory category, long count) {
        return FcmCategoryResponse.builder()
                .label(category.name())
                .name(getCategoryName(category))
                .count(count)
                .build();
    }

    private static String getCategoryName(FcmCategory category) {
        return switch (category) {
            case ALL -> "전체";
            case ALBUM -> "앨범";
            case TARGET -> "목표";
            case HEALTH_SCHEDULE -> "방문 일정";
            case WALK -> "만보계";
            case MEDICINE_SCHEDULE -> "복약 알림";
            case HEART_MESSAGE -> "가족 메시지";
            case SAFE_ZONE -> "안전구역";
            case INCIDENT_SELF_CHECK -> "본인확인";
            case ETC -> "기타";
        };
    }
}