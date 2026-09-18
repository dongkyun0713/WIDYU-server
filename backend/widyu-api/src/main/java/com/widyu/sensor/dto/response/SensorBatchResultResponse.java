package com.widyu.sensor.dto.response;

public record SensorBatchResultResponse(Long seq, SensorBatchResult result) {

    public static SensorBatchResultResponse of(Long seq, SensorBatchResult result) {
        return new SensorBatchResultResponse(seq, result);
    }
}
