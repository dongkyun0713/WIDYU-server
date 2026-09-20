package com.widyu.run.controller;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.run.application.RunExportService;
import com.widyu.run.controller.docs.AdminRunExportDocs;
import com.widyu.run.dto.response.RunExportResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/collection-runs/{runId}/exports")
public class AdminRunExportController implements AdminRunExportDocs {

    private final RunExportService runExportService;

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponseTemplate<RunExportResponse> requestExport(@PathVariable String runId) {
        return ApiResponseTemplate.ok()
                .code("202")
                .message("내보내기를 접수했습니다.")
                .body(runExportService.request(runId));
    }

    @Override
    @GetMapping("/{exportId}")
    public ApiResponseTemplate<RunExportResponse> getExport(
            @PathVariable String runId, @PathVariable String exportId) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("내보내기 상태를 조회했습니다.")
                .body(runExportService.status(runId, exportId));
    }
}
