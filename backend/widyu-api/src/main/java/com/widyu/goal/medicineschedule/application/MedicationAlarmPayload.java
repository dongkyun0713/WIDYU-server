package com.widyu.goal.medicineschedule.application;

import java.util.Map;

/**
 * 약 관련 FCM에 싣는 복약 알람 동기화 data. 앱은 받으면 alarm-sync API로 스냅샷을 다시 조회한다.
 * revision은 알람 스케줄의 버전, 곧 스케줄 주인(시니어)의 medicationAlarmRevision이다. 보호자에게 가는 알림도 같다. → LLD-0037
 */
public final class MedicationAlarmPayload {

    public static final String TYPE = "MEDICATION_SCHEDULE_CHANGED";

    private MedicationAlarmPayload() {
    }

    public static Map<String, String> of(long revision) {
        return Map.of("type", TYPE, "revision", Long.toString(revision));
    }
}
