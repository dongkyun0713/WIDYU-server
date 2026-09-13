package com.widyu.admin.controller;

import com.widyu.admin.application.AdminFcmService;
import com.widyu.admin.application.AdminPointGrantService;
import com.widyu.admin.application.InMemoryLogAppender;
import com.widyu.admin.dto.request.AdminFcmTestRequest;
import com.widyu.admin.dto.request.AdminPointGrantRequest;
import com.widyu.admin.dto.response.AdminLogEntryResponse;
import com.widyu.admin.dto.response.AdminMemberResponse;
import com.widyu.global.response.ApiResponseTemplate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile({"local", "dev"})
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
public class AdminFcmController {

    private final AdminFcmService adminFcmService;
    private final AdminPointGrantService adminPointGrantService;

    @GetMapping("/members")
    public ApiResponseTemplate<List<AdminMemberResponse>> getMembers(
            @RequestParam(required = false) String name) {
        List<AdminMemberResponse> members;
        if (name != null && !name.isBlank()) {
            members = adminFcmService.searchMembers(name).stream().map(AdminMemberResponse::from).toList();
        } else {
            members = adminFcmService.getAllMembers().stream().map(AdminMemberResponse::from).toList();
        }

        return ApiResponseTemplate.ok()
                .code("200")
                .message("회원 목록 조회 성공")
                .body(members);
    }

    @PostMapping("/fcm/test")
    public ApiResponseTemplate<String> sendTestNotification(@RequestBody AdminFcmTestRequest request) {
        String result = adminFcmService.sendTestNotification(request);
        return ApiResponseTemplate.ok()
                .code("200")
                .message("FCM 테스트 발송 완료")
                .body(result);
    }

    @GetMapping("/dev/logs")
    public ApiResponseTemplate<List<AdminLogEntryResponse>> getLogs(
            @RequestParam(required = false) String level,
            @RequestParam(defaultValue = "200") int limit) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("로그 조회 성공")
                .body(InMemoryLogAppender.getEntries(level, limit));
    }

    @DeleteMapping("/dev/logs")
    public ApiResponseTemplate<Void> clearLogs() {
        InMemoryLogAppender.clear();
        return ApiResponseTemplate.ok().code("200").message("로그 초기화 완료").body(null);
    }

    @PostMapping("/dev/points")
    public ApiResponseTemplate<Long> grantPoints(@RequestBody AdminPointGrantRequest request) {
        long newBalance = adminPointGrantService.grant(request.memberId(), request.amount());
        return ApiResponseTemplate.ok()
                .code("200")
                .message("포인트 지급 완료")
                .body(newBalance);
    }
}
