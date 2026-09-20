package com.widyu.admin.controller;

import com.widyu.admin.application.AdminAccessLogService;
import com.widyu.admin.controller.docs.AdminAccessLogDocs;
import com.widyu.admin.dto.response.AdminAccessLogResponse;
import com.widyu.admin.dto.response.AdminPageResponse;
import com.widyu.global.response.ApiResponseTemplate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
public class AdminAccessLogController implements AdminAccessLogDocs {

    private final AdminAccessLogService adminAccessLogService;

    @Override
    @GetMapping("/access-logs")
    public ApiResponseTemplate<AdminPageResponse<AdminAccessLogResponse>> getAccessLogs(
            @RequestParam(required = false) Long adminId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("관리자 접속기록 조회 성공")
                .body(adminAccessLogService.search(adminId, from, to, page, size));
    }
}
