package com.widyu.run.controller;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.run.application.CollectionRunService;
import com.widyu.run.controller.docs.AdminCollectionRunDocs;
import com.widyu.run.dto.request.CollectionRunCloseRequest;
import com.widyu.run.dto.request.CollectionRunOpenRequest;
import com.widyu.run.dto.request.DeviceAssignRequest;
import com.widyu.run.dto.request.DeviceUnassignRequest;
import com.widyu.run.dto.request.RunMarkerRequest;
import com.widyu.run.dto.response.CollectionRunResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 운영자(연구자)가 관리자 계정으로 호출한다. 인가는 `/api/v1/admin/**` 기존 규칙을 따른다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/collection-runs")
public class AdminCollectionRunController implements AdminCollectionRunDocs {

    private final CollectionRunService collectionRunService;

    @Override
    @PostMapping
    public ApiResponseTemplate<CollectionRunResponse> openRun(
            @Valid @RequestBody CollectionRunOpenRequest request) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("측정회차를 열었습니다.")
                .body(collectionRunService.open(request));
    }

    @Override
    @GetMapping("/{runId}")
    public ApiResponseTemplate<CollectionRunResponse> getRun(@PathVariable String runId) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("측정회차를 조회했습니다.")
                .body(collectionRunService.get(runId));
    }

    @Override
    @PatchMapping("/{runId}/close")
    public ApiResponseTemplate<CollectionRunResponse> closeRun(
            @PathVariable String runId,
            @Valid @RequestBody(required = false) CollectionRunCloseRequest request) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("측정회차를 닫았습니다.")
                .body(collectionRunService.close(runId, request));
    }

    @Override
    @PostMapping("/{runId}/devices")
    public ApiResponseTemplate<CollectionRunResponse> addDevice(
            @PathVariable String runId,
            @Valid @RequestBody DeviceAssignRequest request) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("기기를 배정했습니다.")
                .body(collectionRunService.addDevice(runId, request));
    }

    @Override
    @PatchMapping("/{runId}/devices/{assignmentId}/unassign")
    public ApiResponseTemplate<CollectionRunResponse> unassignDevice(
            @PathVariable String runId,
            @PathVariable String assignmentId,
            @Valid @RequestBody(required = false) DeviceUnassignRequest request) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("기기 배정을 해제했습니다.")
                .body(collectionRunService.unassignDevice(runId, assignmentId, request));
    }

    @Override
    @PostMapping("/{runId}/markers")
    public ApiResponseTemplate<CollectionRunResponse> registerMarker(
            @PathVariable String runId,
            @Valid @RequestBody RunMarkerRequest request) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("마커를 등록했습니다.")
                .body(collectionRunService.registerMarker(runId, request));
    }
}
