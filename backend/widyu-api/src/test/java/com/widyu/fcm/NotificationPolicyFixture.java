package com.widyu.fcm;

import java.util.stream.Stream;

/** 기존 발행 동작의 회귀 검증용 값. 운영 알림 종류 레지스트리가 아니다. */
public final class NotificationPolicyFixture {
    private NotificationPolicyFixture() {}
    public static Stream<NotificationPolicy> policies() {
        return Stream.of(
                NotificationPolicy.album("A01"),
                NotificationPolicy.album("A02-S/C"),
                NotificationPolicy.album("A03"),
                NotificationPolicy.album("A04"),
                NotificationPolicy.album("A05"),
                NotificationPolicy.album("A06-L/Z"),
                NotificationPolicy.album("A07"),
                NotificationPolicy.medication("M02"),
                NotificationPolicy.medication("M03"),
                NotificationPolicy.medication("M04"),
                NotificationPolicy.medication("M05"),
                NotificationPolicy.medication("M08"),
                NotificationPolicy.medication("M09"),
                NotificationPolicy.medicationSync(),
                NotificationPolicy.health("H01-S/C-SELF/C-SENIOR-OS/INAPP"),
                NotificationPolicy.health("H02"),
                NotificationPolicy.health("H03"),
                NotificationPolicy.health("H04"),
                NotificationPolicy.walk("W01"),
                NotificationPolicy.walk("W02"),
                NotificationPolicy.goal("G01-S/C"),
                NotificationPolicy.goal("G02-S/C"),
                NotificationPolicy.point("P01"),
                NotificationPolicy.point("P02"),
                NotificationPolicy.message("X01-OS/INAPP"),
                NotificationPolicy.message("X02-OS/INAPP"),
                NotificationPolicy.selfCheck(),
                NotificationPolicy.safety("S01/S03/S04/S06", true),
                NotificationPolicy.safety("K01", true),
                NotificationPolicy.safety("K02", false),
                NotificationPolicy.safety("S08", false),
                NotificationPolicy.safeZone("Z01"),
                NotificationPolicy.safeZone("Z02"),
                NotificationPolicy.familyLeader());
    }
    public static Stream<NotificationPolicy> safeZones() {
        return Stream.of(NotificationPolicy.safeZone("Z01"), NotificationPolicy.safeZone("Z02"));
    }
}
