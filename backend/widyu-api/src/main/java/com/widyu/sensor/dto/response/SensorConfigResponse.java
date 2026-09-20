package com.widyu.sensor.dto.response;

import com.widyu.global.properties.SensorProperties;

/**
 * 워치가 내려받는 수집 설정(LLD-0046 3절).
 * {@code collectionMode}만 호출자에 따라 다르고 나머지는 서버 설정값이다.
 */
public record SensorConfigResponse(
        String collectionMode,
        String gyroMode,
        int accFsHz,
        int batchSec,
        double impactThresholdG,
        int backfillBeforeSec,
        int backfillAfterSec,
        int hrUploadSec,
        int locationMoveSec,
        int locationKeepaliveSec,
        int heartbeatSec,
        int refreshSec
) {

    public static SensorConfigResponse of(SensorProperties.Config config, String collectionMode) {
        return new SensorConfigResponse(
                collectionMode,
                config.gyroMode(),
                config.accFsHz(),
                config.batchSec(),
                config.impactThresholdG(),
                config.backfillBeforeSec(),
                config.backfillAfterSec(),
                config.hrUploadSec(),
                config.locationMoveSec(),
                config.locationKeepaliveSec(),
                config.heartbeatSec(),
                config.refreshSec());
    }
}
