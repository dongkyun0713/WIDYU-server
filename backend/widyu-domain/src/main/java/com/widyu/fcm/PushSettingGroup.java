package com.widyu.fcm;

import com.widyu.member.MemberType;
import java.util.stream.Stream;
import lombok.Getter;

/** 알림 타입과 회원별 푸시 설정에 공통으로 쓰는 그룹. */
@Getter
public enum PushSettingGroup {
    SAFETY("안전 알림", true),
    SAFE_ZONE("안심구역", true),
    MEDICATION_CHECK("복약 확인", false),
    GENERAL("일반 알림", false),
    ALBUM("앨범", false),
    GOAL("목표", false),
    HOME("홈", false),
    ETC("기타", false),
    NONE("", false);

    private final String description;
    private final boolean mandatoryForLeader;

    PushSettingGroup(String description, boolean mandatoryForLeader) {
        this.description = description;
        this.mandatoryForLeader = mandatoryForLeader;
    }

    public static Stream<PushSettingGroup> stream() {
        return Stream.of(values()).filter(group -> group != NONE);
    }

    public boolean visibleFor(MemberType memberType) {
        if (memberType == MemberType.SENIOR) {
            return this == ALBUM || this == GOAL || this == HOME || this == ETC;
        }
        return this == SAFETY || this == SAFE_ZONE || this == MEDICATION_CHECK || this == GENERAL;
    }

    public static PushSettingGroup forSenior(FcmCategory category) {
        if (category == null) {
            return null;
        }
        return switch (category) {
            case ALBUM -> ALBUM;
            case TARGET, HEALTH_SCHEDULE, WALK, MEDICINE_SCHEDULE -> GOAL;
            case HEART_MESSAGE -> HOME;
            case ETC -> ETC;
            case ALL, SAFE_ZONE, INCIDENT_SELF_CHECK, LOCATION_NOTICE -> null;
        };
    }

    /** type이 없는 기존 outbox의 전환기 폴백. null은 개인 설정을 적용하지 않는다. */
    public static PushSettingGroup fromLegacy(FcmCategory category) {
        if (category == null) {
            return null;
        }
        return switch (category) {
            case MEDICINE_SCHEDULE -> MEDICATION_CHECK;
            case SAFE_ZONE -> SAFE_ZONE;
            case ALBUM, TARGET, HEALTH_SCHEDULE, WALK, HEART_MESSAGE, ETC -> GENERAL;
            case ALL, INCIDENT_SELF_CHECK, LOCATION_NOTICE -> null;
        };
    }

    public static PushSettingGroup fromLegacy(FcmCategory category, MemberType memberType) {
        if (memberType == MemberType.SENIOR) {
            return forSenior(category);
        }
        return fromLegacy(category);
    }
}
