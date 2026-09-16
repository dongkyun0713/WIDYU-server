package com.widyu.study.controller;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.study.application.StudyParticipationService;
import com.widyu.study.dto.request.StudyParticipationCreateRequest;
import com.widyu.study.dto.request.StudyRetentionChangeRequest;
import com.widyu.study.dto.response.StudyParticipationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 연구 참여 등록 Admin API. `/api/v1/admin/**`는 SecurityConfig에서 ROLE_ADMIN만 허용한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/studies/participations")
public class AdminStudyParticipationController {

    private final StudyParticipationService studyParticipationService;

    @PostMapping
    public ApiResponseTemplate<StudyParticipationResponse> register(
            @Valid @RequestBody StudyParticipationCreateRequest request) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("연구 참여 등록 성공")
                .body(studyParticipationService.register(request));
    }

    @GetMapping("/{participationId}")
    public ApiResponseTemplate<StudyParticipationResponse> get(@PathVariable String participationId) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("연구 참여 조회 성공")
                .body(studyParticipationService.get(participationId));
    }

    @PatchMapping("/{participationId}/retention")
    public ApiResponseTemplate<StudyParticipationResponse> changeRetention(
            @PathVariable String participationId,
            @Valid @RequestBody StudyRetentionChangeRequest request) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("연구 참여 보존 기간 변경 성공")
                .body(studyParticipationService.changeRetention(participationId, request));
    }
}
