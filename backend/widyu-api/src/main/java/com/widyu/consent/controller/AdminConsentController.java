package com.widyu.consent.controller;

import com.widyu.consent.application.ConsentService;
import com.widyu.consent.controller.docs.AdminConsentDocs;
import com.widyu.consent.dto.response.ConsentRecordResponse;
import com.widyu.global.response.ApiResponseTemplate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자의 회원별 동의 이력 조회. 경로는 기존 관리자 회원 경로를 따르지만
 * 동의 도메인 안에 둔다(LLD-0055 3절). 인가는 {@code /api/v1/admin/**} 규칙이 맡는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
public class AdminConsentController implements AdminConsentDocs {

    private final ConsentService consentService;

    @Override
    @GetMapping("/members/{memberId}/consents")
    public ApiResponseTemplate<List<ConsentRecordResponse>> getConsentHistory(
            @PathVariable Long memberId) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("회원 동의 이력 조회 성공")
                .body(consentService.historyForAdmin(memberId));
    }
}
