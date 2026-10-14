package com.widyu.admin.controller;

import com.widyu.admin.application.AdminFcmService;
import com.widyu.admin.application.AdminMemberService;
import com.widyu.admin.dto.response.AdminMemberDetailFullResponse;
import com.widyu.admin.dto.response.AdminMemberDetailResponse;
import com.widyu.admin.dto.response.AdminPageResponse;
import com.widyu.global.entity.Status;
import com.widyu.global.response.ApiResponseTemplate;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
public class AdminMemberController {

    private final AdminMemberService adminMemberService;
    private final AdminFcmService adminFcmService;

    @GetMapping("/members/list")
    public ApiResponseTemplate<AdminPageResponse<AdminMemberDetailResponse>> getMemberList(
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page는 0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size는 1 이상 100 이하여야 합니다.")
            @Max(value = 100, message = "size는 1 이상 100 이하여야 합니다.") int size) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("회원 목록 조회 성공")
                .body(adminFcmService.getMemberPage(name, page, size));
    }

    @GetMapping("/members/{memberId}")
    public ApiResponseTemplate<AdminMemberDetailFullResponse> getMemberDetail(@PathVariable Long memberId) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("회원 상세 조회 성공")
                .body(adminMemberService.getMemberDetail(memberId));
    }

    @PatchMapping("/members/{memberId}/status")
    public ApiResponseTemplate<String> changeStatus(
            @PathVariable Long memberId,
            @RequestParam Status status) {
        Status updated = adminMemberService.changeStatus(memberId, status);
        return ApiResponseTemplate.ok()
                .code("200")
                .message("상태 변경 성공")
                .body(updated.name());
    }
}
