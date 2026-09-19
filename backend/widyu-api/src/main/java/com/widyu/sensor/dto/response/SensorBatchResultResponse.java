package com.widyu.sensor.dto.response;

public record SensorBatchResultResponse(String batchId, Long seq, SensorBatchResult result) {

    public static SensorBatchResultResponse of(String batchId, Long seq, SensorBatchResult result) {
        return new SensorBatchResultResponse(batchId, seq, result);
    }
}
