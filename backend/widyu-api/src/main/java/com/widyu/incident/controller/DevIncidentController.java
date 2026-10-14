package com.widyu.incident.controller;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.global.util.SecurityUtil;
import com.widyu.incident.application.DevIncidentService;
import com.widyu.incident.controller.docs.DevIncidentDocs;
import com.widyu.incident.dto.request.DevHeartEmergencyRequest;
import com.widyu.incident.dto.response.IncidentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 프론트 테스트용(개발·로컬 전용). 운영 프로필에서는 등록되지 않아 404다. */
@Profile({"local", "dev"})
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/dev/incidents")
public class DevIncidentController implements DevIncidentDocs {

    private final DevIncidentService devIncidentService;
    private final SecurityUtil securityUtil;

    @Override
    @PostMapping("/heart-emergency")
    public ApiResponseTemplate<IncidentResponse> triggerHeartEmergency(
            @RequestBody(required = false) DevHeartEmergencyRequest request) {
        IncidentResponse response = devIncidentService.triggerHeartEmergency(
                securityUtil.getCurrentMemberId(), request);
        return ApiResponseTemplate.ok().code("INCIDENT_2010").message("개발용 심박 위급 발생").body(response);
    }
}
