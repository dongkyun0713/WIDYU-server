package com.widyu.consent.controller;

import com.widyu.consent.application.ConsentService;
import com.widyu.consent.controller.docs.ConsentDocs;
import com.widyu.consent.dto.request.ConsentSubmitRequest;
import com.widyu.consent.dto.request.ConsentWithdrawRequest;
import com.widyu.consent.dto.response.ConsentStateResponse;
import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.global.util.SecurityUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/consents")
public class ConsentController implements ConsentDocs {

    private final ConsentService consentService;
    private final SecurityUtil securityUtil;

    @Override
    @PutMapping
    public ApiResponseTemplate<ConsentStateResponse> submitConsents(
            @Valid @RequestBody ConsentSubmitRequest request) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("동의를 기록했습니다.")
                .body(consentService.submit(securityUtil.getCurrentMemberId(), request));
    }

    @Override
    @GetMapping
    public ApiResponseTemplate<ConsentStateResponse> getCurrentConsents() {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("현재 동의 상태 조회 성공")
                .body(consentService.currentState(securityUtil.getCurrentMemberId()));
    }

    @Override
    @PostMapping("/withdrawal")
    public ApiResponseTemplate<ConsentStateResponse> withdrawConsents(
            @Valid @RequestBody ConsentWithdrawRequest request) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("동의 철회를 기록했습니다.")
                .body(consentService.withdraw(securityUtil.getCurrentMemberId(), request));
    }
}
