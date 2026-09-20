package com.widyu.device.controller;

import com.widyu.device.application.DeviceHeartbeatService;
import com.widyu.device.controller.docs.DeviceHeartbeatDocs;
import com.widyu.device.dto.response.DeviceHeartbeatResponse;
import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.global.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/device")
public class DeviceHeartbeatController implements DeviceHeartbeatDocs {

    private final DeviceHeartbeatService deviceHeartbeatService;
    private final SecurityUtil securityUtil;

    /**
     * 본문을 DTO가 아니라 원문 바이트로 받는다. 저장한 payload가 앱이 보낸 본문과
     * 같아야 하므로(LLD-0049 3절) 재직렬화 지점을 두지 않는다. 파싱·검증은 서비스가 한다.
     */
    @Override
    @PostMapping(value = "/heartbeats", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponseTemplate<DeviceHeartbeatResponse> ingestHeartbeat(@RequestBody byte[] payload) {
        DeviceHeartbeatResponse response =
                deviceHeartbeatService.ingest(securityUtil.getCurrentMemberId(), payload);
        return ApiResponseTemplate.ok()
                .code("200")
                .message("기기 하트비트를 저장했습니다.")
                .body(response);
    }
}
