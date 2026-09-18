package com.widyu.sensor.controller;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.global.util.SecurityUtil;
import com.widyu.sensor.application.SensorBatchService;
import com.widyu.sensor.controller.docs.SensorBatchDocs;
import com.widyu.sensor.dto.request.SensorBatchRequest;
import com.widyu.sensor.dto.response.SensorBatchResultResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/sensor")
public class SensorBatchController implements SensorBatchDocs {

    private final SensorBatchService sensorBatchService;
    private final SecurityUtil securityUtil;

    @Override
    @PostMapping("/batches")
    public ApiResponseTemplate<SensorBatchResultResponse> ingestBatch(
            @Valid @RequestBody SensorBatchRequest request
    ) {
        SensorBatchResultResponse response =
                sensorBatchService.ingest(securityUtil.getCurrentMemberId(), request);
        return ApiResponseTemplate.ok()
                .code("200")
                .message("센서 배치를 저장했습니다.")
                .body(response);
    }
}
