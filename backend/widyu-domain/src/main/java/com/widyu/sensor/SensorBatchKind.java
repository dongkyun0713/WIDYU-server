package com.widyu.sensor;

/** 배치 전송 종류. 재전송·보강은 원 seq와 원 시각을 유지한다(정책 1.3.2). */
public enum SensorBatchKind {
    LIVE,
    RETRANSMIT,
    GYRO_ENRICH
}
