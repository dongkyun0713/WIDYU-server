package com.widyu.heart.controller;

import com.widyu.global.annotation.ValidateFamilyAccess;
import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.global.util.SecurityUtil;
import com.widyu.heart.application.HeartMessageService;
import com.widyu.heart.application.HeartRateService;
import com.widyu.heart.controller.docs.HeartRateDocs;
import com.widyu.heart.dto.request.HeartMessageRequest;
import com.widyu.heart.dto.response.HeartGraphPageResponse;
import com.widyu.heart.dto.response.HeartRateStatusResponse;
import com.widyu.heart.dto.response.RecentEmergencyResponse;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/heart-rate")
public class HeartRateController implements HeartRateDocs {

    private final HeartRateService heartRateService;
    private final HeartMessageService heartMessageService;
    private final SecurityUtil securityUtil;

    @Override
    @GetMapping("/status")
    @ValidateFamilyAccess(memberIdParam = "memberId")
    public ApiResponseTemplate<HeartRateStatusResponse> getHeartRateStatus(
            @RequestParam(required = false) Long memberId
    ) {
        HeartRateStatusResponse response = heartRateService.getHeartRateStatus(resolveMemberId(memberId));
        return ApiResponseTemplate.ok()
                .code("HEART_2001")
                .message("심박수 이상치 조회 완료")
                .body(response);
    }

    @Override
    @GetMapping("/emergency/recent")
    @ValidateFamilyAccess(memberIdParam = "memberId")
    public ApiResponseTemplate<RecentEmergencyResponse> getRecentEmergency(
            @RequestParam(required = false) Long memberId
    ) {
        RecentEmergencyResponse response = heartRateService.getRecentEmergency(resolveMemberId(memberId));
        return ApiResponseTemplate.ok()
                .code("HEART_2006")
                .message("최근 심박수 위험 감지 여부 조회 완료")
                .body(response);
    }

    @Override
    @GetMapping("/graph")
    @ValidateFamilyAccess(memberIdParam = "memberId")
    public ApiResponseTemplate<HeartGraphPageResponse> getHeartGraph(
            @RequestParam(required = false) Long memberId
    ) {
        HeartGraphPageResponse response = heartRateService.getHeartGraph(resolveMemberId(memberId));
        return ApiResponseTemplate.ok()
                .code("HEART_2004")
                .message("심박수 그래프 조회 완료")
                .body(response);
    }

    @Override
    @GetMapping("/graph/refresh")
    @ValidateFamilyAccess(memberIdParam = "memberId")
    public ApiResponseTemplate<HeartGraphPageResponse> getHeartGraphRefresh(
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since
    ) {
        HeartGraphPageResponse response = heartRateService.getHeartGraphRefresh(resolveMemberId(memberId), since);
        return ApiResponseTemplate.ok()
                .code("HEART_2005")
                .message("심박수 그래프 갱신 완료")
                .body(response);
    }

    @Override
    @PostMapping("/message")
    public ApiResponseTemplate<Void> sendHeartMessage(
            @Valid @RequestBody HeartMessageRequest request
    ) {
        heartMessageService.sendHeartMessage(request);
        return ApiResponseTemplate.ok()
                .code("HEART_2003")
                .message("알림 발송 요청을 접수했습니다")
                .build();
    }

    private Long resolveMemberId(Long memberId) {
        if (memberId != null) {
            return memberId;
        }
        return securityUtil.getCurrentMemberId();
    }
}
