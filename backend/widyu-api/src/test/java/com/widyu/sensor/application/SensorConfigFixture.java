package com.widyu.sensor.application;

import com.widyu.global.properties.SensorProperties;

/** `application-sensor.yml`의 기본값과 같은 설정. 배치 픽스처가 싣고 오는 값과 맞춰 둔다. */
final class SensorConfigFixture {

    private SensorConfigFixture() {
    }

    static SensorProperties properties() {
        return new SensorProperties(32_768, config(), export(), fallAi());
    }

    static SensorProperties.Config config() {
        return new SensorProperties.Config("continuous", 50, 1, 1.8, 2, 10, 1, 5, 60, 60, 60);
    }

    /** 내보내기 설정. `application-sensor.yml`의 기본값과 같다. */
    static SensorProperties.Export export() {
        return new SensorProperties.Export(
                5000L,
                900_000L,
                15,
                "test-build",
                java.util.Map.of("imu_watch", 20L, "imu_phone", 20L, "hr", 1000L,
                        "location", 60000L, "heartbeat", 60000L),
                java.util.Map.of("imu_watch", 5000L, "imu_phone", 5000L, "hr", 5000L,
                        "location", 120000L, "heartbeat", 120000L),
                new SensorProperties.Export.Clock(
                        "DEVICE_MONOTONIC", "UTC_EPOCH_MS", "ANCHOR_PAIR", "APP_REPORTED_ANCHOR"),
                "NO_DEVICE_ASSIGNED",
                "NO_DATA_IN_THIS_RUN",
                "NOT_IMPLEMENTED_IN_THIS_RUN");
    }

    static SensorProperties.FallAi fallAi() {
        return new SensorProperties.FallAi(false, "/api/fall", 2, "widyu-server", "abstain-v1");
    }
}
