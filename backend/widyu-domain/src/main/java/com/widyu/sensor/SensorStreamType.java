package com.widyu.sensor;

/** 원시 센서 스트림 종류(LLD-0041 3.1). 가속도와 자이로는 독립 스트림이다. */
public enum SensorStreamType {
    WATCH_ACCEL,
    WATCH_GYRO,
    PHONE_ACCEL,
    PHONE_GYRO,
    PHONE_LOCATION
}
