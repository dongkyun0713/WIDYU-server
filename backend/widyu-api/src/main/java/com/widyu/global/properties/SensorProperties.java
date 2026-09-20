package com.widyu.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sensor")
public record SensorProperties(
        int maxPayloadBytes,
        Config config,
        Export export
) {

    /**
     * 워치에 내려보내는 수집 설정(지시서 B12, 정책 1.6.4).
     * 참가자별 값이 아니라 실증 프로토콜 단위 운영값이다.
     */
    public record Config(
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
    ) {}

    /** 내보내기 설정(LLD-0050 4절). 값 집합이 미정인 상수(X1·X8·X9)를 여기에 모은다. */
    public record Export(
            long pollDelayMs,
            long runningTimeoutMs,
            int presignMinutes,
            String serverBuild,
            java.util.Map<String, Long> expectedPeriodMs,
            java.util.Map<String, Long> gapThresholdMs,
            Clock clock,
            String absentReasonRoleMissing,
            String absentReasonNoData,
            String absentReasonNotImplemented
    ) {

        public record Clock(
                String sourceDomain,
                String targetDomain,
                String transform,
                String evidenceMethod
        ) {}
    }
}
