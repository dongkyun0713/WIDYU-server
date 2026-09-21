package com.widyu.run.application;

import com.widyu.device.DeviceHeartbeat;
import com.widyu.location.raw.LocationFix;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.run.CollectionRun;
import com.widyu.run.CollectionRunStatus;
import com.widyu.run.RunDeviceAssignment;
import com.widyu.run.RunMarker;
import com.widyu.sensor.ClockMapping;
import com.widyu.sensor.SensorBatch;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * LLD-0050 7절 세 번째 인수조건의 합성 회차.
 * 워치 1대(imu_watch 3배치·hr 2배치)·폰 1대(location 3건·heartbeat 2건)·마커 1개·매핑 1개.
 */
final class RunExportFixture {

    static final Long MEMBER_ID = 1023L;
    static final String RUN_ID = "run-0f3a";
    static final String WATCH_DEVICE = "gw-3f2a";
    static final String PHONE_DEVICE = "ph-9c1";
    static final String SESSION_ID = "s-20260920-01";
    static final String CLOCK_MAPPING_ID = "cm-01";
    static final long STARTED_AT_MS = 1_760_000_000_000L;
    static final long ENDED_AT_MS = 1_760_000_180_000L;
    static final long ANCHOR_ELAPSED_NS = 993_847_100_000_001L;

    private RunExportFixture() {
    }

    static Member member() {
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        ReflectionTestUtils.setField(member, "id", MEMBER_ID);
        return member;
    }

    static CollectionRun closedRun() {
        CollectionRun run = CollectionRun.builder()
                .runId(RUN_ID)
                .member(member())
                .studyId("STUDY-2026")
                .participationId("P-001")
                .protocolRef("PROTO-A")
                .consentVersion("v1")
                .collectionMode("product")
                .startedAtMs(STARTED_AT_MS)
                .status(CollectionRunStatus.OPEN)
                .qualityNotes("정상 종료")
                .build();
        ReflectionTestUtils.setField(run, "id", 7L);
        run.close(ENDED_AT_MS, "정상 종료", null);
        return run;
    }

    static RunDeviceAssignment assignment(String deviceId, String role) {
        return RunDeviceAssignment.builder()
                .assignmentId("asg-" + deviceId)
                .run(closedRun())
                .deviceId(deviceId)
                .role(role)
                .wearSite("LEFT_WRIST")
                .assignedAtMs(STARTED_AT_MS)
                .build();
    }

    static RunMarker marker() {
        return RunMarker.builder()
                .markerId("01j8zmark0000000000000001")
                .run(closedRun())
                .kind("TASK_START")
                .label("sit_to_stand")
                .sourceElapsedNs(55_120_000_001L)
                .tsMs(STARTED_AT_MS + 10_020L)
                .source("OPERATOR_APP")
                .sourceDeviceId("op-1")
                .clockMappingId(CLOCK_MAPPING_ID)
                .build();
    }

    static ClockMapping clockMapping() {
        return clockMapping(ANCHOR_ELAPSED_NS, ANCHOR_ELAPSED_NS + 180_000_000_000L);
    }

    static ClockMapping clockMapping(long observedMinElapsedNs, long observedMaxElapsedNs) {
        return ClockMapping.builder()
                .clockMappingId(CLOCK_MAPPING_ID)
                .deviceId(WATCH_DEVICE)
                .bootId("b7c1")
                .anchorElapsedNs(ANCHOR_ELAPSED_NS)
                .anchorEpochMs(STARTED_AT_MS)
                .uncertaintyMs(2.0)
                .observedMinElapsedNs(observedMinElapsedNs)
                .observedMaxElapsedNs(observedMaxElapsedNs)
                .firstSeenAtMs(STARTED_AT_MS + 5L)
                .lastSeenAtMs(ENDED_AT_MS)
                .build();
    }

    static SensorBatch imuBatch(long id, long seq, long startMs, long endMs, boolean backfill) {
        SensorBatch batch = SensorBatch.builder()
                .batchId("01j8zimu%018d".formatted(seq))
                .member(member())
                .stream("imu_watch")
                .source("watch")
                .deviceId(WATCH_DEVICE)
                .sessionId(SESSION_ID)
                .seq(seq)
                .studyId("STUDY-2026")
                .participationId("P-001")
                .runId(RUN_ID)
                .bootId("b7c1")
                .clockMappingId(CLOCK_MAPPING_ID)
                .anchorElapsedNs(ANCHOR_ELAPSED_NS)
                .anchorEpochMs(STARTED_AT_MS)
                .uncertaintyMs(2.0)
                .accN(3)
                .accT0ElapsedNs(ANCHOR_ELAPSED_NS)
                .accFsHzRequested(50.0)
                .measuredAtStartMs(startMs)
                .measuredAtEndMs(endMs)
                .serverReceivedAtMs(endMs + 120)
                .acceptedAtMs(endMs + 125)
                .persistedAtMs(endMs + 140)
                .modelAvailableAtServerMs(endMs + 140)
                .collectionMode("product")
                .gyroMode("continuous")
                .onBody(true)
                .watchBatteryPct(63)
                .qualityStatus("OK")
                .gyroBackfill(backfill)
                .isResend(false)
                .configMismatch(false)
                .s3Key("sensor/1023/%s/imu_watch/%d.json".formatted(WATCH_DEVICE, seq))
                .byteSize(512)
                .payloadSha256("a".repeat(64))
                .build();
        if (backfill) {
            ReflectionTestUtils.setField(batch, "backfillFor", "01j8zimu000000000000000001,01j8zimu000000000000000002");
            ReflectionTestUtils.setField(batch, "accN", null);
            ReflectionTestUtils.setField(batch, "gyroN", 3);
        }
        ReflectionTestUtils.setField(batch, "id", id);
        return batch;
    }

    static SensorBatch resentHrBatch(long id, long seq, long startMs, long endMs) {
        SensorBatch batch = SensorBatch.builder()
                .batchId("01j8zhr0%018d".formatted(seq))
                .member(member())
                .stream("hr")
                .source("watch")
                .deviceId(WATCH_DEVICE)
                .sessionId(SESSION_ID)
                .seq(seq)
                .studyId("STUDY-2026")
                .participationId("P-001")
                .runId(RUN_ID)
                .bootId("b7c1")
                .clockMappingId(CLOCK_MAPPING_ID)
                .anchorElapsedNs(ANCHOR_ELAPSED_NS)
                .anchorEpochMs(STARTED_AT_MS)
                .uncertaintyMs(2.0)
                .sampleCount(3)
                .measuredAtStartMs(startMs)
                .measuredAtEndMs(endMs)
                .serverReceivedAtMs(endMs + 120)
                .acceptedAtMs(endMs + 125)
                .persistedAtMs(endMs + 140)
                .modelAvailableAtServerMs(endMs + 140)
                .onBody(true)
                .watchBatteryPct(63)
                .qualityStatus("OK")
                .gyroBackfill(false)
                .isResend(true)
                .originalBatchId("01j8zhr0000000000000000001")
                .originalSeq(seq - 1)
                .originalRunId(RUN_ID)
                .originalSessionId("s-20260920-00")
                .resentAtMs(endMs + 60_000)
                .configMismatch(false)
                .s3Key("sensor/1023/%s/hr/%d.json".formatted(WATCH_DEVICE, seq))
                .byteSize(256)
                .payloadSha256("b".repeat(64))
                .build();
        ReflectionTestUtils.setField(batch, "id", id);
        return batch;
    }

    /** 배치마다 t0를 달리해 봉투 시각과 페이로드 환산 시각이 맞게 한다(검사기 B11). */
    static long t0ElapsedNs(long startMs) {
        return ANCHOR_ELAPSED_NS + (startMs - STARTED_AT_MS) * 1_000_000L;
    }

    static String imuPayload(long seq, long startMs, boolean backfill) {
        String axis = """
                {"fs_hz_requested": 50, "n": 3, "t0_elapsed_ns": "%d",
                   "dt_ns": [19998417, 20003005],
                   "%s": [[20, -980, 110], [22, -979, 108], [21, -981, 109]]}""";
        String acc = axis.formatted(t0ElapsedNs(startMs), "mg");
        String gyro = "null";
        if (backfill) {
            // 보강 배치는 자이로만 싣는다(계약 §2.2).
            acc = "null";
            gyro = axis.formatted(t0ElapsedNs(startMs), "mrads");
        }
        return """
                {"v": 2, "stream": "imu_watch", "source": "watch", "batch_id": "01j8zimu%018d",
                 "device_id": "%s", "session_id": "%s", "seq": %d,
                 "study_id": "STUDY-2026", "participation_id": "P-001", "run_id": "%s",
                 "clock": {"boot_id": "b7c1", "clock_mapping_id": "cm-01",
                   "anchor_elapsed_ns": "993847100000001", "anchor_epoch_ms": 1760000000000,
                   "uncertainty_ms": 2.0},
                 "acc": %s, "gyro": %s, "trigger": null,
                 "gyro_backfill": %s, "backfill_for": null,
                 "collection_mode": "product", "gyro_mode": "continuous",
                 "on_body": true, "wear_state": null, "missing_reason": null, "watch_battery_pct": 63}"""
                .formatted(seq, WATCH_DEVICE, SESSION_ID, seq, RUN_ID, acc, gyro, backfill);
    }

    static String hrPayload(long seq, long startMs) {
        return """
                {"v": 2, "stream": "hr", "batch_id": "01j8zhr0%018d",
                 "device_id": "%s", "session_id": "%s", "seq": %d,
                 "study_id": "STUDY-2026", "participation_id": "P-001", "run_id": "%s",
                 "clock": {"boot_id": "b7c1", "clock_mapping_id": "cm-01",
                   "anchor_elapsed_ns": "993847100000001", "anchor_epoch_ms": 1760000000000,
                   "uncertainty_ms": 2.0},
                 "samples": [{"bpm": 71, "ts_ms": %d, "accuracy": "HIGH"},
                             {"bpm": 72, "ts_ms": %d, "accuracy": "HIGH"},
                             {"bpm": 0, "ts_ms": %d, "accuracy": "UNRELIABLE"}],
                 "on_body": true, "watch_battery_pct": 63}"""
                .formatted(seq, WATCH_DEVICE, SESSION_ID, seq, RUN_ID,
                        startMs, startMs + 1000, startMs + 2000);
    }

    static LocationFix locationFix(long id, long seq, long tsMs) {
        String payload = """
                {"v": 2, "stream": "location", "device_id": "%s", "session_id": "%s", "seq": %d,
                 "study_id": "STUDY-2026", "participation_id": "P-001", "run_id": "%s",
                 "ts_ms": %d, "lat": 37.5665, "lon": 126.978, "accuracy_m": 12.5,
                 "speed_mps": 1.2, "speed_accuracy_mps": 0.4, "heading_deg": 91.0,
                 "altitude_m": 38.0, "provider": "fused", "is_mock": false, "reason": "move"}"""
                .formatted(PHONE_DEVICE, SESSION_ID, seq, RUN_ID, tsMs);
        LocationFix fix = LocationFix.builder()
                .memberId(MEMBER_ID)
                .deviceId(PHONE_DEVICE)
                .sessionId(SESSION_ID)
                .seq(seq)
                .studyId("STUDY-2026")
                .participationId("P-001")
                .runId(RUN_ID)
                .tsMs(tsMs)
                .lat(37.5665)
                .lon(126.978)
                .accuracyM(12.5)
                .provider("fused")
                .isMock(false)
                .reason("move")
                .serverReceivedAtMs(tsMs + 100)
                .acceptedAtMs(tsMs + 105)
                .persistedAtMs(tsMs + 120)
                .payload(payload)
                .payloadSha256("c".repeat(64))
                .build();
        ReflectionTestUtils.setField(fix, "id", id);
        return fix;
    }

    static DeviceHeartbeat heartbeat(long id, long tsMs, boolean watchOnBody) {
        String payload = """
                {"v": 2, "stream": "heartbeat", "device_id": "%s", "session_id": "%s",
                 "study_id": "STUDY-2026", "participation_id": "P-001", "run_id": "%s",
                 "ts_ms": %d,
                 "phone": {"battery_pct": 71, "charging": false, "os": "android-14",
                   "app_ver": "1.2.0", "socket_connected": true, "location_permission": "ALWAYS",
                   "background_restricted": false, "queue_depth": 0},
                 "watch": {"connected": true, "device_id": "%s", "battery_pct": 63,
                   "app_ver": "1.2.0", "hr_session": "RUNNING", "on_body": %s,
                   "last_hr_ts_ms": %d, "last_imu_ts_ms": %d, "queue_depth": 0}}"""
                .formatted(PHONE_DEVICE, SESSION_ID, RUN_ID, tsMs, WATCH_DEVICE, watchOnBody,
                        tsMs - 1000, tsMs - 500);
        DeviceHeartbeat heartbeat = DeviceHeartbeat.builder()
                .memberId(MEMBER_ID)
                .deviceId(PHONE_DEVICE)
                .sessionId(SESSION_ID)
                .tsMs(tsMs)
                .studyId("STUDY-2026")
                .participationId("P-001")
                .runId(RUN_ID)
                .phoneBatteryPct(71)
                .socketConnected(true)
                .phoneQueueDepth(0)
                .watchConnected(true)
                .watchDeviceId(WATCH_DEVICE)
                .watchBatteryPct(63)
                .watchOnBody(watchOnBody)
                .lastImuTsMs(tsMs - 500)
                .serverReceivedAtMs(tsMs + 100)
                .acceptedAtMs(tsMs + 105)
                .persistedAtMs(tsMs + 120)
                .payload(payload)
                .payloadSha256("d".repeat(64))
                .build();
        ReflectionTestUtils.setField(heartbeat, "id", id);
        return heartbeat;
    }

    /** `application-sensor.yml`의 내보내기 기본값과 같다. */
    static com.widyu.global.properties.SensorProperties properties() {
        return new com.widyu.global.properties.SensorProperties(
                32_768,
                new com.widyu.global.properties.SensorProperties.Config(
                        "continuous", 50, 1, 1.8, 2, 10, 1, 5, 60, 60, 60),
                new com.widyu.global.properties.SensorProperties.Export(
                        5000L,
                        900_000L,
                        15,
                        "test-build",
                        java.util.Map.of("imu_watch", 20L, "imu_phone", 20L, "hr", 1000L,
                                "location", 60000L, "heartbeat", 60000L),
                        java.util.Map.of("imu_watch", 5000L, "imu_phone", 5000L, "hr", 5000L,
                                "location", 120000L, "heartbeat", 120000L),
                        new com.widyu.global.properties.SensorProperties.Export.Clock(
                                "DEVICE_MONOTONIC", "UTC_EPOCH_MS", "ANCHOR_PAIR", "APP_REPORTED_ANCHOR"),
                        "NO_DEVICE_ASSIGNED",
                        "NO_DATA_IN_THIS_RUN"),
                new com.widyu.global.properties.SensorProperties.FallAi(
                        false, "/api/fall", 2, "widyu-server", "abstain-v1"),
                new com.widyu.global.properties.SensorProperties.HeartAi("widyu-ai-hr", "ver7"),
                new com.widyu.global.properties.SensorProperties.Incident(45, 5000L));
    }

    // ── 현실 분량 합성 회차(LLD-0050 7절) ──────────────────────────
    // 180초 · imu_watch 1초 배치 180건(각 50샘플) + 충격 보강 1건 · hr 3초 배치 60건
    // · location 5초 36건 · heartbeat 60초 3건. 배치 경계는 이어져 갭이 없다.

    static final int IMU_BATCH_COUNT = 180;
    static final int IMU_SAMPLES = 50;
    static final int HR_BATCH_COUNT = 60;
    static final long TRIGGER_BATCH_INDEX = 100L;

    /** 합성 회차 한 벌. 페이로드는 S3 키로 찾는다. */
    record SyntheticRun(
            java.util.List<SensorBatch> imuBatches,
            java.util.List<SensorBatch> hrBatches,
            java.util.List<LocationFix> locationFixes,
            java.util.List<DeviceHeartbeat> heartbeats,
            java.util.Map<String, String> payloadsByS3Key,
            long observedMinElapsedNs,
            long observedMaxElapsedNs) {}

    /** 같은 값이 반복되면 검사기가 보간 흔적으로 본다(A5). 배치·샘플마다 흔든다. */
    private static long dtNs(int batchIndex, int sampleIndex) {
        return 20_000_000L + ((batchIndex + sampleIndex) % 7 - 3) * 137L + (sampleIndex % 3);
    }

    private static long sumDtNs(int batchIndex) {
        long total = 0L;
        for (int i = 0; i < IMU_SAMPLES - 1; i++) {
            total += dtNs(batchIndex, i);
        }
        return total;
    }

    /** 충격 샘플만 SMV 3.2g가 되게 둔다. 나머지는 정지에 가깝다(검사기 G2가 재계산 대조). */
    private static final int IMPACT_SAMPLE_INDEX = 25;
    private static final int IMPACT_MG_Z = 3200;
    private static final int GYRO_WINDOW_FROM = 98;
    private static final int GYRO_WINDOW_TO = 110;

    private static final long GYRO_AXIS_OFFSET_NS = 7_000_000L;

    private static String axisJson(
            long t0ElapsedNs, int batchIndex, String valuesField, boolean impact) {
        boolean gyroAxis = "mrads".equals(valuesField);
        long axisT0 = t0ElapsedNs;
        int jitterSeed = batchIndex;
        if (gyroAxis) {
            // 가속도와 자이로는 시간축이 독립이다(계약 §2.2). 같은 축을 복사하면 A11이 잡는다.
            axisT0 += GYRO_AXIS_OFFSET_NS;
            jitterSeed += 3;
        }
        StringBuilder dt = new StringBuilder();
        for (int i = 0; i < IMU_SAMPLES - 1; i++) {
            if (i > 0) {
                dt.append(", ");
            }
            dt.append(dtNs(jitterSeed, i));
        }
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < IMU_SAMPLES; i++) {
            if (i > 0) {
                values.append(", ");
            }
            if (impact && i == IMPACT_SAMPLE_INDEX) {
                values.append("[0, 0, %d]".formatted(IMPACT_MG_Z));
                continue;
            }
            values.append("[%d, %d, %d]".formatted(20 + i % 5, -980 + i % 3, 110 - i % 4));
        }
        return """
                {"fs_hz_requested": 50, "n": %d, "t0_elapsed_ns": "%d", "dt_ns": [%s], "%s": [%s]}"""
                .formatted(IMU_SAMPLES, axisT0, dt, valuesField, values);
    }

    /** 충격 샘플의 부팅 기준 시각. trigger의 event_elapsed_ns·ts_ms가 이 값을 가리켜야 한다. */
    private static long impactElapsedNs(long t0ElapsedNs, int batchIndex) {
        long elapsed = t0ElapsedNs;
        for (int i = 0; i < IMPACT_SAMPLE_INDEX; i++) {
            elapsed += dtNs(batchIndex, i);
        }
        return elapsed;
    }

    /** 충격 앞 2초·뒤 10초는 계약이 자이로를 의무화한 구간이다(정책 1.6.4). */
    private static boolean gyroInWindow(int batchIndex) {
        return batchIndex >= GYRO_WINDOW_FROM && batchIndex <= GYRO_WINDOW_TO;
    }

    private static String imuBatchJson(
            long seq, long t0ElapsedNs, int batchIndex, boolean backfill, boolean trigger) {
        String acc = axisJson(t0ElapsedNs, batchIndex, "mg", trigger);
        String gyro = "null";
        if (backfill || gyroInWindow(batchIndex)) {
            // 보강 배치는 자이로만, 충격 창 안의 배치는 가속도와 함께 자이로를 싣는다.
            gyro = axisJson(t0ElapsedNs, batchIndex, "mrads", false);
        }
        if (backfill) {
            acc = "null";
        }
        String backfillForJson = "null";
        if (backfill) {
            backfillForJson = "[\"01j8zimu%018d\"]".formatted(batchIndex + 1);
        }
        String triggerJson = "null";
        if (trigger) {
            long impactNs = impactElapsedNs(t0ElapsedNs, batchIndex);
            long impactMs = STARTED_AT_MS + (impactNs - ANCHOR_ELAPSED_NS) / 1_000_000L;
            triggerJson = """
                    {"kind": "impact", "smv_g": 3.2, "event_elapsed_ns": "%d", "ts_ms": %d}"""
                    .formatted(impactNs, impactMs);
        }
        return """
                {"v": 2, "stream": "imu_watch", "source": "watch", "batch_id": "01j8zimu%018d",
                 "device_id": "%s", "session_id": "%s", "seq": %d,
                 "study_id": "STUDY-2026", "participation_id": "P-001", "run_id": "%s",
                 "clock": {"boot_id": "b7c1", "clock_mapping_id": "cm-01",
                   "anchor_elapsed_ns": "993847100000001", "anchor_epoch_ms": 1760000000000,
                   "uncertainty_ms": 2.0},
                 "acc": %s, "gyro": %s, "trigger": %s,
                 "gyro_backfill": %s, "backfill_for": %s,
                 "collection_mode": "product", "gyro_mode": "trigger",
                 "on_body": true, "wear_state": null, "missing_reason": null, "watch_battery_pct": 63}"""
                .formatted(seq, WATCH_DEVICE, SESSION_ID, seq, RUN_ID, acc, gyro, triggerJson,
                        backfill, backfillForJson);
    }

    private static SensorBatch imuBatchEntity(
            long id, long seq, long startMs, long endMs, long t0ElapsedNs, boolean backfill) {
        SensorBatch batch = imuBatch(id, seq, startMs, endMs, backfill);
        ReflectionTestUtils.setField(batch, "accT0ElapsedNs", t0ElapsedNs);
        ReflectionTestUtils.setField(batch, "accN", IMU_SAMPLES);
        ReflectionTestUtils.setField(batch, "gyroN", null);
        if (gyroInWindow((int) (startMs - STARTED_AT_MS) / 1000)) {
            ReflectionTestUtils.setField(batch, "gyroN", IMU_SAMPLES);
            ReflectionTestUtils.setField(batch, "gyroT0ElapsedNs", t0ElapsedNs + 7_000_000L);
        }
        ReflectionTestUtils.setField(batch, "collectionMode", "product");
        ReflectionTestUtils.setField(batch, "gyroMode", "trigger");
        if (backfill) {
            ReflectionTestUtils.setField(batch, "accN", null);
            ReflectionTestUtils.setField(batch, "accT0ElapsedNs", null);
            ReflectionTestUtils.setField(batch, "gyroN", IMU_SAMPLES);
            ReflectionTestUtils.setField(batch, "gyroT0ElapsedNs", t0ElapsedNs + 7_000_000L);
            ReflectionTestUtils.setField(batch, "backfillFor",
                    "01j8zimu%018d".formatted(GYRO_WINDOW_FROM));
        }
        return batch;
    }

    static SyntheticRun syntheticRun() {
        java.util.List<SensorBatch> imuBatches = new java.util.ArrayList<>();
        java.util.List<SensorBatch> hrBatches = new java.util.ArrayList<>();
        java.util.List<LocationFix> locationFixes = new java.util.ArrayList<>();
        java.util.List<DeviceHeartbeat> heartbeats = new java.util.ArrayList<>();
        java.util.Map<String, String> payloads = new java.util.HashMap<>();
        long maxElapsedNs = 0L;

        for (int i = 0; i < IMU_BATCH_COUNT; i++) {
            long seq = i + 1L;
            long startMs = STARTED_AT_MS + i * 1000L;
            long t0 = t0ElapsedNs(startMs);
            long lastElapsedNs = t0 + sumDtNs(i);
            long endMs = STARTED_AT_MS + (lastElapsedNs - ANCHOR_ELAPSED_NS) / 1_000_000L;
            maxElapsedNs = Math.max(maxElapsedNs, lastElapsedNs);
            SensorBatch batch = imuBatchEntity(seq, seq, startMs, endMs, t0, false);
            imuBatches.add(batch);
            payloads.put(batch.getS3Key(),
                    imuBatchJson(seq, t0, i, false, i == TRIGGER_BATCH_INDEX));
        }
        // 충격 직전 구간을 자이로로 보강해 오는 배치. 원 ACC 배치를 가리킨다(계약 §2.2).
        int backfillIndex = GYRO_WINDOW_FROM - 1;
        long backfillStartMs = STARTED_AT_MS + backfillIndex * 1000L;
        long backfillT0 = t0ElapsedNs(backfillStartMs);
        long backfillEndMs =
                STARTED_AT_MS + (backfillT0 + sumDtNs(backfillIndex) - ANCHOR_ELAPSED_NS) / 1_000_000L;
        long backfillSeq = IMU_BATCH_COUNT + 1L;
        SensorBatch backfill = imuBatchEntity(
                backfillSeq, backfillSeq, backfillStartMs, backfillEndMs, backfillT0, true);
        imuBatches.add(backfill);
        payloads.put(backfill.getS3Key(), imuBatchJson(backfillSeq, backfillT0, backfillIndex, true, false));

        for (int k = 0; k < HR_BATCH_COUNT; k++) {
            long seq = 1000L + k;
            long startMs = STARTED_AT_MS + k * 3000L;
            SensorBatch batch = resentHrBatch(seq, seq, startMs, startMs + 2000L);
            if (k > 0) {
                ReflectionTestUtils.setField(batch, "isResend", false);
                ReflectionTestUtils.setField(batch, "originalBatchId", null);
                ReflectionTestUtils.setField(batch, "originalSeq", null);
                ReflectionTestUtils.setField(batch, "originalRunId", null);
                ReflectionTestUtils.setField(batch, "originalSessionId", null);
                ReflectionTestUtils.setField(batch, "resentAtMs", null);
            }
            hrBatches.add(batch);
            payloads.put(batch.getS3Key(), hrPayload(seq, startMs));
        }

        for (int j = 0; j < 36; j++) {
            locationFixes.add(locationFix(j + 1L, j + 1L, STARTED_AT_MS + j * 5000L));
        }
        for (int h = 0; h < 3; h++) {
            heartbeats.add(heartbeat(h + 1L, STARTED_AT_MS + h * 60_000L, true));
        }
        return new SyntheticRun(imuBatches, hrBatches, locationFixes, heartbeats, payloads,
                t0ElapsedNs(STARTED_AT_MS), maxElapsedNs + 1L);
    }
}
