package com.widyu.sensor.controller;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.global.util.SecurityUtil;
import com.widyu.sensor.application.SensorConfigService;
import com.widyu.sensor.controller.docs.SensorConfigDocs;
import com.widyu.sensor.dto.response.SensorConfigResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/sensor")
public class SensorConfigController implements SensorConfigDocs {

    private final SensorConfigService sensorConfigService;
    private final SecurityUtil securityUtil;

    @Override
    @GetMapping("/config")
    public ApiResponseTemplate<SensorConfigResponse> getConfig() {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("수집 설정을 조회했습니다.")
                .body(sensorConfigService.currentConfig(securityUtil.getCurrentMemberId()));
    }
}
