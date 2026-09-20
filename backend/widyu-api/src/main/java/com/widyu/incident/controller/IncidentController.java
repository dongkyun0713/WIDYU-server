package com.widyu.incident.controller;

import com.widyu.global.annotation.ValidateFamilyAccess;
import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.global.util.SecurityUtil;
import com.widyu.incident.application.IncidentService;
import com.widyu.incident.controller.docs.IncidentDocs;
import com.widyu.incident.dto.request.IncidentRespondRequest;
import com.widyu.incident.dto.request.IncidentResolveRequest;
import com.widyu.incident.dto.response.IncidentResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/incidents")
public class IncidentController implements IncidentDocs {

    private final IncidentService incidentService;
    private final SecurityUtil securityUtil;

    @Override
    @PostMapping("/{incidentId}/response")
    public ApiResponseTemplate<IncidentResponse> respond(
            @PathVariable String incidentId,
            @Valid @RequestBody IncidentRespondRequest request
    ) {
        IncidentResponse response = incidentService.respond(
                securityUtil.getCurrentMemberId(), incidentId, request);
        return ApiResponseTemplate.ok()
                .code("INCIDENT_2001")
                .message("본인확인 응답 완료")
                .body(response);
    }

    @Override
    @PostMapping("/{incidentId}/outcome")
    public ApiResponseTemplate<IncidentResponse> resolve(
            @PathVariable String incidentId,
            @Valid @RequestBody IncidentResolveRequest request
    ) {
        IncidentResponse response = incidentService.resolve(
                securityUtil.getCurrentMemberId(), incidentId, request);
        return ApiResponseTemplate.ok()
                .code("INCIDENT_2002")
                .message("사후 판정 완료")
                .body(response);
    }

    @Override
    @GetMapping
    @ValidateFamilyAccess(memberIdParam = "seniorId")
    public ApiResponseTemplate<List<IncidentResponse>> getIncidents(
            @RequestParam Long seniorId,
            @RequestParam(required = false) String state
    ) {
        List<IncidentResponse> responses = incidentService.findForSenior(seniorId, state);
        return ApiResponseTemplate.ok()
                .code("INCIDENT_2003")
                .message("인시던트 목록 조회 완료")
                .body(responses);
    }

    @Override
    @GetMapping("/mine/pending")
    public ApiResponseTemplate<List<IncidentResponse>> getMyPendingIncidents() {
        List<IncidentResponse> responses = incidentService.findPending(securityUtil.getCurrentMemberId());
        return ApiResponseTemplate.ok()
                .code("INCIDENT_2004")
                .message("응답 대기 인시던트 조회 완료")
                .body(responses);
    }
}
