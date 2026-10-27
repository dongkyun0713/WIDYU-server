package com.widyu.fcm;

public enum FcmCategory {
    ALL,
    /** 안 읽은 알림 전체를 보는 조회 전용 값. 알림 행에 저장하지 않는다. */
    UNREAD,
    ALBUM,
    TARGET,
    HEALTH_SCHEDULE,
    WALK,
    MEDICINE_SCHEDULE,
    HEART_MESSAGE,
    SAFE_ZONE,
    /** 위급 판정 뒤 시니어 본인에게 보내는 확인 푸시(LLD-0054 3절). */
    INCIDENT_SELF_CHECK,
    /** 보호자가 내 위치를 봤다는 통보(위치정보법 제19조③④, LLD-0056 5절). 설정으로 끄지 않는다 */
    LOCATION_NOTICE,
    ETC
}
