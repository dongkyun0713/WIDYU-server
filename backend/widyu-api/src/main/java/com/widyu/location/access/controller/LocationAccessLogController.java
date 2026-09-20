package com.widyu.location.access.controller;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.global.util.SecurityUtil;
import com.widyu.location.access.application.LocationAccessLogService;
import com.widyu.location.access.controller.docs.LocationAccessLogDocs;
import com.widyu.location.access.dto.response.LocationAccessLogResponse;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시니어 본인만 자기 열람 기록을 본다. 대상이 언제나 현재 회원이라 경로에 {@code memberId}가
 * 없고, 그래서 남의 기록을 볼 수 있는 입구 자체가 없다(LLD-0056 5.5).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/location/access-logs")
public class LocationAccessLogController implements LocationAccessLogDocs {

    private final LocationAccessLogService locationAccessLogService;
    private final SecurityUtil securityUtil;

    @Override
    @GetMapping("/mine")
    public ApiResponseTemplate<Page<LocationAccessLogResponse>> getMyAccessLogs(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("위치 열람 기록 조회 성공")
                .body(locationAccessLogService.myLogs(
                        securityUtil.getCurrentMemberId(), from, to, page, size));
    }
}
