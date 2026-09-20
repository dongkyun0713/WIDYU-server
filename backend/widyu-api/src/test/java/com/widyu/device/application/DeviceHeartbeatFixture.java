package com.widyu.device.application;

/** 지시서 부록 B 하트비트 본문. 테스트마다 바꾸는 조각만 인자로 받는다(LLD-0049 3절). */
final class DeviceHeartbeatFixture {

    static final Long MEMBER_ID = 1L;
    static final String DEVICE_ID = "ph-9c1";
    static final String SESSION_ID = "s-20260920-01";
    static final Long TS_MS = 1_760_000_000_123L;

    static final String PHONE = """
            { "battery_pct": 71, "charging": false, "os": "android/14", "app_ver": "1.4.2",
              "socket_connected": true, "location_permission": "always",
              "background_restricted": false, "queue_depth": 0 }""";

    static final String PHONE_WITHOUT_BATTERY = """
            { "charging": false, "os": "android/14", "app_ver": "1.4.2",
              "socket_connected": true, "location_permission": "always",
              "background_restricted": false, "queue_depth": 0 }""";

    static final String WATCH_CONNECTED = """
            { "connected": true, "device_id": "gw-3f2a", "battery_pct": 63, "app_ver": "1.4.2",
              "hr_session": "RUNNING", "on_body": true, "last_hr_ts_ms": 1760000000000,
              "last_imu_ts_ms": 1760000000100, "queue_depth": 0 }""";

    static final String WATCH_DISCONNECTED = """
            { "connected": false, "device_id": null, "battery_pct": null, "app_ver": null,
              "hr_session": null, "on_body": null, "last_hr_ts_ms": null,
              "last_imu_ts_ms": null, "queue_depth": null }""";

    static final String WATCH_OFF_BODY = """
            { "connected": true, "device_id": "gw-3f2a", "battery_pct": 63, "app_ver": "1.4.2",
              "hr_session": "STOPPED", "on_body": false, "last_hr_ts_ms": 1760000000000,
              "last_imu_ts_ms": 1760000000100, "queue_depth": 12 }""";

    private DeviceHeartbeatFixture() {
    }

    static String heartbeat(String phone, String watch) {
        return """
                { "v": 1, "ts_ms": %d, "device_id": "%s", "session_id": "%s",
                  "study_id": null, "participation_id": null, "run_id": null,
                  "phone": %s, "watch": %s }""".formatted(TS_MS, DEVICE_ID, SESSION_ID, phone, watch);
    }
}
