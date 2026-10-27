package com.widyu.sensor.application;

import com.widyu.device.DeviceHeartbeat;
import com.widyu.location.raw.LocationFix;
import com.widyu.sensor.SensorBatch;
import com.widyu.sensor.dto.request.HeartRateBatchRequest;
import com.widyu.sensor.dto.request.SensorBatchRequest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 시간대 내보내기의 CSV 행 규칙(LLD-0108 3절). 상태가 없어 시각 환산·구간 경계·phase를 단위 테스트로 검증한다.
 * 원문이 손상되면 {@link IllegalArgumentException}을 던지고, 묶음 하나의 행은 다 검증한 뒤에만 돌려준다.
 */
final class SensorWindowCsv {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    static final String LOCATION_HEADER = line("phase", "ts_kst", "ts_ms", "device_id", "session_id", "seq",
            "lat", "lon", "accuracy_m", "speed_mps", "speed_accuracy_mps", "heading_deg", "altitude_m",
            "provider", "is_mock", "reason", "run_id", "server_received_at_ms");
    static final String HR_HEADER = line("phase", "ts_kst", "ts_ms", "device_id", "batch_id", "bpm", "accuracy");
    static final String IMU_HEADER = line("phase", "ts_kst", "ts_ms", "device_id", "stream", "batch_id",
            "sensor", "x", "y", "z", "unit");
    static final String BATCH_HEADER = line("phase", "ts_kst", "ts_ms", "stream", "device_id", "batch_id",
            "session_id", "seq", "measured_start_ms", "measured_end_ms", "acc_n", "gyro_n", "sample_count",
            "on_body", "wear_state", "missing_reason", "watch_battery_pct", "quality_status", "collection_mode",
            "gyro_mode", "trigger_kind", "server_received_at_ms");
    static final String HEARTBEAT_HEADER = line("phase", "ts_kst", "ts_ms", "device_id", "watch_device_id",
            "watch_connected", "watch_on_body", "watch_battery_pct", "phone_battery_pct", "phone_charging",
            "socket_connected", "last_hr_ts_ms", "last_imu_ts_ms", "location_permission", "background_restricted",
            "phone_queue_depth", "watch_queue_depth", "phone_app_ver", "watch_app_ver", "phone_os");

    private static final DateTimeFormatter KST_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(KST);
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private SensorWindowCsv() {
    }

    /** 요청 시각(from·to)과 여유를 붙인 구간(start·end). 모두 UTC epoch ms이며 양 끝을 포함한다. */
    record Window(long fromMs, long toMs, long startMs, long endMs) {

        boolean contains(long tsMs) {
            return tsMs >= startMs && tsMs <= endMs;
        }

        String phase(long tsMs) {
            if (tsMs < fromMs) {
                return "before";
            }
            if (tsMs > toMs) {
                return "after";
            }
            return "during";
        }
    }

    static String kst(long epochMs) {
        return KST_FORMAT.format(Instant.ofEpochMilli(epochMs));
    }

    static String location(LocationFix fix, Window window) {
        long ts = fix.getTsMs();
        return line(window.phase(ts), kst(ts), ts, fix.getDeviceId(), fix.getSessionId(), fix.getSeq(),
                fix.getLat(), fix.getLon(), fix.getAccuracyM(), fix.getSpeedMps(), fix.getSpeedAccuracyMps(),
                fix.getHeadingDeg(), fix.getAltitudeM(), fix.getProvider(), fix.getIsMock(), fix.getReason(),
                fix.getRunId(), fix.getServerReceivedAtMs());
    }

    static String heartbeat(DeviceHeartbeat heartbeat, Window window) {
        long ts = heartbeat.getTsMs();
        return line(window.phase(ts), kst(ts), ts, heartbeat.getDeviceId(), heartbeat.getWatchDeviceId(),
                heartbeat.getWatchConnected(), heartbeat.getWatchOnBody(), heartbeat.getWatchBatteryPct(),
                heartbeat.getPhoneBatteryPct(), heartbeat.getPhoneCharging(), heartbeat.getSocketConnected(),
                heartbeat.getLastHrTsMs(), heartbeat.getLastImuTsMs(), heartbeat.getLocationPermission(),
                heartbeat.getBackgroundRestricted(), heartbeat.getPhoneQueueDepth(), heartbeat.getWatchQueueDepth(),
                heartbeat.getPhoneAppVer(), heartbeat.getWatchAppVer(), heartbeat.getPhoneOs());
    }

    /** 묶음의 phase·시각은 측정 시작 기준이다. 구간과 겹치는 묶음만 들어오므로 시작이 구간 앞일 수 있다. */
    static String batch(SensorBatch batch, Window window) {
        long ts = batch.getMeasuredAtStartMs();
        return line(window.phase(ts), kst(ts), ts, batch.getStream(), batch.getDeviceId(), batch.getBatchId(),
                batch.getSessionId(), batch.getSeq(), batch.getMeasuredAtStartMs(), batch.getMeasuredAtEndMs(),
                batch.getAccN(), batch.getGyroN(), batch.getSampleCount(), batch.getOnBody(), batch.getWearState(),
                batch.getMissingReason(), batch.getWatchBatteryPct(), batch.getQualityStatus(),
                batch.getCollectionMode(), batch.getGyroMode(), batch.getTriggerKind(),
                batch.getServerReceivedAtMs());
    }

    static List<String> heartRows(SensorBatch batch, HeartRateBatchRequest request, Window window) {
        if (request.samples() == null || !batch.getBatchId().equals(request.batchId())) {
            throw new IllegalArgumentException("심박 원문과 묶음 목록이 다름");
        }
        List<String> rows = new ArrayList<>();
        for (HeartRateBatchRequest.Sample sample : request.samples()) {
            if (sample == null || sample.tsMs() == null || sample.bpm() == null) {
                throw new IllegalArgumentException("심박 표본 손상");
            }
            long ts = sample.tsMs();
            if (!window.contains(ts)) {
                continue;
            }
            rows.add(line(window.phase(ts), kst(ts), ts, batch.getDeviceId(), batch.getBatchId(),
                    sample.bpm(), sample.accuracy()));
        }
        return rows;
    }

    static List<String> imuRows(SensorBatch batch, SensorBatchRequest request, Window window) {
        if (request.clock() == null || !batch.getBatchId().equals(request.batchId())) {
            throw new IllegalArgumentException("IMU 원문과 묶음 목록이 다름");
        }
        List<String> rows = new ArrayList<>();
        addAxis(rows, batch, request, request.acc(), "acc", window);
        addAxis(rows, batch, request, request.gyro(), "gyro", window);
        return rows;
    }

    /** 표본 시각 = anchor_epoch_ms + floor((t0 + 누적 dt - anchor_elapsed) / 1e6). 닫은 PR #880과 같은 식이다. */
    private static void addAxis(List<String> rows, SensorBatch batch, SensorBatchRequest request,
                                SensorBatchRequest.Axis axis, String sensor, Window window) {
        if (axis == null) {
            return;
        }
        List<List<Integer>> values = axis.mrads();
        String unit = "mrad_s";
        if ("acc".equals(sensor)) {
            values = axis.mg();
            unit = "mg";
        }
        if (axis.n() == null || axis.n() < 1 || values == null || values.size() != axis.n()
                || axis.dtNs() == null || axis.dtNs().size() != axis.n() - 1 || axis.t0ElapsedNs() == null) {
            throw new IllegalArgumentException("IMU 축 구조 손상");
        }
        try {
            long elapsedNs = Long.parseLong(axis.t0ElapsedNs());
            long anchorElapsedNs = Long.parseLong(request.clock().anchorElapsedNs());
            long anchorEpochMs = request.clock().anchorEpochMs();
            for (int index = 0; index < axis.n(); index++) {
                if (index > 0) {
                    elapsedNs = Math.addExact(elapsedNs, axis.dtNs().get(index - 1));
                }
                long ts = Math.addExact(anchorEpochMs,
                        Math.floorDiv(Math.subtractExact(elapsedNs, anchorElapsedNs), NANOS_PER_MILLI));
                if (!window.contains(ts)) {
                    continue;
                }
                List<Integer> sample = values.get(index);
                if (sample == null || sample.size() != 3 || sample.contains(null)) {
                    throw new IllegalArgumentException("IMU 표본 손상");
                }
                rows.add(line(window.phase(ts), kst(ts), ts, batch.getDeviceId(), batch.getStream(),
                        batch.getBatchId(), sensor, sample.get(0), sample.get(1), sample.get(2), unit));
            }
        } catch (ArithmeticException | NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException("IMU 시각 환산 불가");
        }
    }

    static String line(Object... values) {
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                line.append(',');
            }
            line.append(escape(values[index]));
        }
        return line.append('\n').toString();
    }

    /** 스프레드시트가 수식으로 읽는 문자로 시작하는 문자열은 앞에 작은따옴표를 붙인다. 숫자(음수 좌표 등)는 그대로 둔다. */
    private static String escape(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (value instanceof CharSequence && startsLikeFormula(text)) {
            text = "'" + text;
        }
        if (text.indexOf(',') < 0 && text.indexOf('"') < 0 && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private static boolean startsLikeFormula(String text) {
        if (text.isEmpty()) {
            return false;
        }
        char first = text.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }
}
