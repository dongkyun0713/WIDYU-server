package com.widyu.sensor.application;

/**
 * 지시서 부록 A 형식의 배치 JSON. 값은 부록 A 예시를 그대로 쓴다.
 * 문자열로 두는 이유는 서버가 원문 바이트를 보관하기 때문이다. DTO를 만들어 직렬화하면
 * 실제로 들어오는 표기(snake_case, 문자열 나노초)를 검증하지 못한다.
 */
final class SensorBatchFixture {

    static final String BATCH_ID = "01j8zk3v9x2q4m7n8p1r5s6t7u";
    static final String DEVICE_ID = "gw-3f2a";
    static final String SESSION_ID = "s-20260919-01";
    static final long SEQ = 88213L;
    static final long ANCHOR_ELAPSED_NS = 993_847_100_000_001L;
    static final long ANCHOR_EPOCH_MS = 1_760_000_000_000L;
    static final long ACC_T0_ELAPSED_NS = 993_847_112_340_001L;

    /** n=3, dt 합 40,001,422ns. 기준점에서 12,340,000ns 뒤에 시작한다. */
    static final String ACC = """
            {
                "fs_hz_requested": 50,
                "n": 3,
                "t0_elapsed_ns": "993847112340001",
                "dt_ns": [19998417, 20003005],
                "mg": [[20, -980, 110], [22, -979, 108], [21, -981, 109]]
              }""";

    /** 가속도와 시간축이 독립이라 샘플 수가 다르다(n=2). */
    static final String GYRO = """
            {
                "fs_hz_requested": 50,
                "n": 2,
                "t0_elapsed_ns": "993847110000001",
                "dt_ns": [19999000],
                "mrads": [[3, -12, 5], [4, -11, 6]]
              }""";

    static final String RESEND = """
            {
                "is_resend": true,
                "original_batch_id": "01j8y000000000000000000000",
                "original_seq": 88210,
                "original_run_id": "run-01",
                "original_session_id": "s-20260919-00",
                "resent_at_ms": 1760000180000
              }""";

    private SensorBatchFixture() {
    }

    static String batch(String acc, String gyro) {
        return batch(acc, gyro, "null", "false", "null", "null");
    }

    static String batch(
            String acc, String gyro, String trigger, String gyroBackfill, String backfillFor, String resend) {
        return """
                {
                  "v": 2,
                  "stream": "imu_watch",
                  "source": "watch",
                  "batch_id": "%s",
                  "device_id": "%s",
                  "session_id": "%s",
                  "seq": %d,
                  "study_id": null,
                  "participation_id": null,
                  "run_id": null,
                  "clock": {
                    "boot_id": "b7c1",
                    "clock_mapping_id": "cm-01",
                    "anchor_elapsed_ns": "993847100000001",
                    "anchor_epoch_ms": 1760000000000,
                    "uncertainty_ms": 2.0
                  },
                  "acc": %s,
                  "gyro": %s,
                  "trigger": %s,
                  "gyro_backfill": %s,
                  "backfill_for": %s,
                  "resend": %s,
                  "collection_mode": "product",
                  "gyro_mode": "continuous",
                  "on_body": true,
                  "wear_state": null,
                  "missing_reason": null,
                  "watch_battery_pct": 63
                }""".formatted(
                BATCH_ID, DEVICE_ID, SESSION_ID, SEQ, acc, gyro, trigger, gyroBackfill, backfillFor, resend);
    }

    /** 축 하나의 필드를 바꾼 블록. 구조 위반 케이스에 쓴다. */
    static String acc(String n, String dtNs, String mg) {
        return """
                {
                    "fs_hz_requested": 50,
                    "n": %s,
                    "t0_elapsed_ns": "993847112340001",
                    "dt_ns": %s,
                    "mg": %s
                  }""".formatted(n, dtNs, mg);
    }
}
