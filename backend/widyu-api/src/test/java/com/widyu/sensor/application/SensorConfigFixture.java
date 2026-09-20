package com.widyu.sensor.application;

import com.widyu.global.properties.SensorProperties;

/** `application-sensor.yml`의 기본값과 같은 설정. 배치 픽스처가 싣고 오는 값과 맞춰 둔다. */
final class SensorConfigFixture {

    private SensorConfigFixture() {
    }

    static SensorProperties properties() {
        return new SensorProperties(32_768, config());
    }

    static SensorProperties.Config config() {
        return new SensorProperties.Config("continuous", 50, 1, 1.8, 2, 10, 1, 5, 60, 60, 60);
    }
}
