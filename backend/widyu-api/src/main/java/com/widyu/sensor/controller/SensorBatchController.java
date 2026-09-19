package com.widyu.sensor.controller;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.global.util.SecurityUtil;
import com.widyu.sensor.application.SensorBatchService;
import com.widyu.sensor.controller.docs.SensorBatchDocs;
import com.widyu.sensor.dto.response.SensorBatchResultResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
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

    /**
     * 본문을 DTO가 아니라 원문 바이트로 받는다. 저장한 것을 꺼냈을 때 앱이 보낸 본문과
     * 바이트 단위로 같아야 하므로(지시서 B2) 재직렬화 지점을 두지 않는다. 파싱·검증은 서비스가 한다.
     */
    @Override
    @PostMapping(value = "/batches", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponseTemplate<SensorBatchResultResponse> ingestBatch(@RequestBody byte[] payload) {
        SensorBatchResultResponse response =
                sensorBatchService.ingest(securityUtil.getCurrentMemberId(), payload);
        return ApiResponseTemplate.ok()
                .code("200")
                .message("센서 배치를 저장했습니다.")
                .body(response);
    }
}
