package com.widyu.admin.controller;

import com.widyu.admin.AdminAction;
import com.widyu.admin.application.AdminAuditLogService;
import com.widyu.admin.dto.response.AdminAuditLogResponse;
import com.widyu.admin.dto.response.AdminPageResponse;
import com.widyu.global.response.ApiResponseTemplate;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
public class AdminAuditLogController {

    private final AdminAuditLogService adminAuditLogService;

    @GetMapping("/audit-logs")
    public ApiResponseTemplate<AdminPageResponse<AdminAuditLogResponse>> getLogs(
            @RequestParam(required = false) AdminAction action,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page는 0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "30")
            @Min(value = 1, message = "size는 1 이상 100 이하여야 합니다.")
            @Max(value = 100, message = "size는 1 이상 100 이하여야 합니다.") int size) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("감사 로그 조회 성공")
                .body(adminAuditLogService.getLogs(action, page, size));
    }
}
