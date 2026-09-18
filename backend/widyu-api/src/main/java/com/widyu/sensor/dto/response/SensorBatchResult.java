package com.widyu.sensor.dto.response;

/** 배치 수신 결과. REJECTED는 WebSocket ACK에서만 쓰인다(LLD-0041 3.2). */
public enum SensorBatchResult {
    STORED,
    DUPLICATE,
    REJECTED
}
