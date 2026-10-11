package com.widyu.notification;

import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.NotificationType;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.MemberType;
import java.util.Arrays;
import java.util.List;

public enum NotificationCenterFilter {
    ALL(List.of()),
    UNREAD(List.of()),
    LOCATION(List.of(FcmCategory.SAFE_ZONE, FcmCategory.LOCATION_NOTICE)),
    ALBUM(List.of(FcmCategory.ALBUM)),
    GOAL(List.of(FcmCategory.TARGET, FcmCategory.HEALTH_SCHEDULE,
            FcmCategory.WALK, FcmCategory.MEDICINE_SCHEDULE)),
    MESSAGE(List.of(FcmCategory.HEART_MESSAGE, FcmCategory.ETC));

    private final List<FcmCategory> legacyCategories;

    NotificationCenterFilter(List<FcmCategory> legacyCategories) {
        this.legacyCategories = legacyCategories;
    }

    public static NotificationCenterFilter parse(String value, MemberType memberType) {
        if (value == null) {
            return ALL;
        }
        try {
            NotificationCenterFilter filter = valueOf(value);
            if (filter == LOCATION && memberType != MemberType.GUARDIAN) {
                throw new BusinessException(ErrorCode.INVALID_NOTIFICATION_CENTER_FILTER);
            }
            return filter;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_NOTIFICATION_CENTER_FILTER);
        }
    }

    public static List<NotificationCenterFilter> countedFilters(MemberType memberType) {
        if (memberType == MemberType.GUARDIAN) {
            return List.of(UNREAD, LOCATION, ALBUM, GOAL, MESSAGE);
        }
        return List.of(UNREAD, ALBUM, GOAL, MESSAGE);
    }

    public static String categoryOfLegacy(FcmCategory category) {
        if (category == null) {
            return null;
        }
        for (NotificationCenterFilter filter : List.of(LOCATION, ALBUM, GOAL, MESSAGE)) {
            if (filter.legacyCategories.contains(category)) {
                return filter.name();
            }
        }
        return null;
    }

    public boolean isCategory() {
        return this != ALL && this != UNREAD;
    }

    public List<FcmCategory> legacyCategories() {
        return legacyCategories;
    }

    public List<NotificationType> types() {
        return Arrays.stream(NotificationType.values())
                .filter(type -> name().equals(type.centerFilter()))
                .toList();
    }
}
