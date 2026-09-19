package com.widyu.sensor.application;

import com.widyu.global.properties.SensorProperties;
import com.widyu.run.application.CollectionRunService;
import com.widyu.sensor.dto.response.SensorConfigResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 워치에 내려보내는 수집 설정(LLD-0046 5절, 지시서 B12).
 * 수집 모드는 참가자별 값이 아니라 <b>열린 측정회차 유무</b>로 정한다. 회차 열기·닫기(#643)가
 * 곧 전환 수단이라 관리자 변경 API를 따로 두지 않는다(합의 대기 K5 회신).
 */
@Service
@RequiredArgsConstructor
public class SensorConfigService {

    private static final String MODE_RESEARCH = "research";
    private static final String MODE_PRODUCT = "product";

    private final SensorProperties sensorProperties;
    private final CollectionRunService collectionRunService;

    @Transactional(readOnly = true)
    public SensorConfigResponse currentConfig(Long memberId) {
        return SensorConfigResponse.of(sensorProperties.config(), collectionModeOf(memberId));
    }

    private String collectionModeOf(Long memberId) {
        if (collectionRunService.hasOpenRun(memberId)) {
            return MODE_RESEARCH;
        }
        return MODE_PRODUCT;
    }
}
