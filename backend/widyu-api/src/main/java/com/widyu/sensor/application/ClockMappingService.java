package com.widyu.sensor.application;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.sensor.ClockMapping;
import com.widyu.sensor.dto.request.SensorBatchRequest;
import com.widyu.sensor.repository.ClockMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배치가 참조하는 시계 환산 기준점 묶음을 등록하고 대조한다(LLD-0044 5.1).
 * 앱이 환산해 온 값을 그대로 믿지 않기 위해, 같은 식별자로 다른 값이 오거나 다른 기기가 같은
 * 식별자를 쓰면 거부한다.
 *
 * <p>등록은 자기 트랜잭션에서 커밋된다. 뒤이어 S3나 배치 저장이 실패해도 매핑 행은 남는데,
 * 매핑은 앱이 그 식별자를 발급했다는 사실의 기록이라 배치 성공 여부와 무관하게 참이다.
 * 앵커 시각·불확실성 값은 어떤 로그에도 남기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClockMappingService {

    private final ClockMappingRepository clockMappingRepository;

    @Transactional
    public void register(
            SensorBatchRequest.Clock clock,
            String deviceId,
            long observedMinElapsedNs,
            long observedMaxElapsedNs
    ) {
        long now = System.currentTimeMillis();
        ClockMapping registered = clockMappingRepository.findByClockMappingId(clock.clockMappingId())
                .orElse(null);

        if (registered == null) {
            try {
                clockMappingRepository.saveAndFlush(
                        newMapping(clock, deviceId, observedMinElapsedNs, observedMaxElapsedNs, now));
                // 새로 만든 행의 관측 범위가 곧 이 배치의 범위라 넓힐 것이 없다.
                return;
            } catch (DataIntegrityViolationException e) {
                // 같은 매핑을 처음 보는 요청 둘이 동시에 왔다. 한 행만 남고 둘 다 대조로 넘어간다.
                registered = clockMappingRepository.findByClockMappingId(clock.clockMappingId())
                        .orElseThrow(() -> e);
            }
        }

        verifySameMapping(registered, clock, deviceId);
        clockMappingRepository.widenObservedRange(
                clock.clockMappingId(), observedMinElapsedNs, observedMaxElapsedNs, now);
    }

    private ClockMapping newMapping(
            SensorBatchRequest.Clock clock,
            String deviceId,
            long observedMinElapsedNs,
            long observedMaxElapsedNs,
            long now
    ) {
        return ClockMapping.builder()
                .clockMappingId(clock.clockMappingId())
                .deviceId(deviceId)
                .bootId(clock.bootId())
                .anchorElapsedNs(anchorElapsedNs(clock))
                .anchorEpochMs(clock.anchorEpochMs())
                .uncertaintyMs(clock.uncertaintyMs())
                .observedMinElapsedNs(observedMinElapsedNs)
                .observedMaxElapsedNs(observedMaxElapsedNs)
                .firstSeenAtMs(now)
                .lastSeenAtMs(now)
                .build();
    }

    private void verifySameMapping(
            ClockMapping registered, SensorBatchRequest.Clock clock, String deviceId) {
        String conflictField = conflictFieldOf(registered, clock, deviceId);
        if (conflictField == null) {
            return;
        }
        // 필드 이름만 남긴다. 앵커 시각·불확실성 값은 남기지 않는다(LLD-0044 6절).
        log.warn("시계 매핑 충돌: clockMappingId={}, deviceId={}, field={}",
                clock.clockMappingId(), deviceId, conflictField);
        throw new BusinessException(ErrorCode.SENSOR_CLOCK_MAPPING_CONFLICT);
    }

    /** 다른 첫 필드의 이름. 전부 같으면 null. */
    private String conflictFieldOf(
            ClockMapping registered, SensorBatchRequest.Clock clock, String deviceId) {
        if (!registered.getDeviceId().equals(deviceId)) {
            return "device_id";
        }
        if (!registered.getBootId().equals(clock.bootId())) {
            return "boot_id";
        }
        if (registered.getAnchorElapsedNs().longValue() != anchorElapsedNs(clock)) {
            return "anchor_elapsed_ns";
        }
        if (registered.getAnchorEpochMs().longValue() != clock.anchorEpochMs().longValue()) {
            return "anchor_epoch_ms";
        }
        if (Double.compare(registered.getUncertaintyMs(), clock.uncertaintyMs()) != 0) {
            return "uncertainty_ms";
        }
        return null;
    }

    /** 형식·범위는 호출자가 이미 검증했다(LLD-0041 5.1 4단계). */
    private long anchorElapsedNs(SensorBatchRequest.Clock clock) {
        return Long.parseLong(clock.anchorElapsedNs());
    }
}
