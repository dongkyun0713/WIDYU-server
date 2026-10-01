package com.widyu.followup.controller;

import com.widyu.followup.application.FollowupCardService;
import com.widyu.followup.controller.docs.FollowupDocs;
import com.widyu.followup.dto.request.FollowupAnswerRequest;
import com.widyu.followup.dto.request.FollowupDeclineRequest;
import com.widyu.followup.dto.response.CurrentFollowupResponse;
import com.widyu.followup.dto.response.FollowupSubmissionResponse;
import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.global.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/followups")
public class FollowupController implements FollowupDocs {
    private final FollowupCardService cardService;
    private final SecurityUtil securityUtil;

    @Override
    @GetMapping("/current")
    public ApiResponseTemplate<CurrentFollowupResponse> current(@RequestParam String visitKey) {
        return ApiResponseTemplate.ok().code("FOLLOWUP_2001").message("후속 카드 조회 완료")
                .body(cardService.current(securityUtil.getCurrentMemberId(), visitKey));
    }

    @Override
    @PostMapping("/{id}/answers")
    public ApiResponseTemplate<FollowupSubmissionResponse> answer(
            @PathVariable Long id, @RequestBody FollowupAnswerRequest request) {
        return ApiResponseTemplate.ok().code("FOLLOWUP_2002").message("후속 답변 저장 완료")
                .body(cardService.answer(securityUtil.getCurrentMemberId(), id, request));
    }

    @Override
    @PostMapping("/{id}/decline")
    public ApiResponseTemplate<FollowupSubmissionResponse> decline(
            @PathVariable Long id, @RequestBody FollowupDeclineRequest request) {
        Long deviceSubmittedAtMs = null;
        if (request != null) {
            deviceSubmittedAtMs = request.deviceSubmittedAtMs();
        }
        return ApiResponseTemplate.ok().code("FOLLOWUP_2003").message("후속 질문 거절 기록 완료")
                .body(cardService.decline(securityUtil.getCurrentMemberId(), id,
                        deviceSubmittedAtMs));
    }
}
