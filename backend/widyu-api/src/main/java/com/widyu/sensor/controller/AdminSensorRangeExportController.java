package com.widyu.sensor.controller;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.sensor.application.SensorRangeExportService;
import com.widyu.sensor.controller.docs.AdminSensorRangeExportDocs;
import com.widyu.sensor.dto.request.SensorRangeExportRequest;
import com.widyu.sensor.dto.response.SensorRangeExportResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/members/{memberId}/sensor-exports")
public class AdminSensorRangeExportController implements AdminSensorRangeExportDocs {

    private final SensorRangeExportService service;

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponseTemplate<SensorRangeExportResponse> request(
            @PathVariable Long memberId, @RequestBody(required = false) SensorRangeExportRequest request) {
        return ApiResponseTemplate.ok()
                .code("202")
                .message("센서 내보내기를 접수했습니다.")
                .body(service.request(memberId, request));
    }

    @Override
    @GetMapping("/{exportId}")
    public ApiResponseTemplate<SensorRangeExportResponse> status(
            @PathVariable Long memberId, @PathVariable String exportId) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("센서 내보내기 상태를 조회했습니다.")
                .body(service.status(memberId, exportId));
    }
}
