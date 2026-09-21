package com.widyu.study.controller.docs;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.study.dto.request.StudyParticipationCreateRequest;
import com.widyu.study.dto.request.StudyRetentionChangeRequest;
import com.widyu.study.dto.request.StudyWithdrawalRequest;
import com.widyu.study.dto.response.StudyParticipationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Admin Study Participation", description = "실증 참여 기록 API (운영자)")
public interface AdminStudyParticipationDocs {

    @Operation(
            summary = "실증 참여 등록",
            description = """
                    서면 연구동의를 받은 참가자의 실증 참여 한 번을 기록합니다. 이 기록이 실증 참여 여부와
                    연구 보관 정책의 정본이고, 연구 회차는 이 기록을 참조합니다.

                    **참여 식별자는 서버가 발급합니다**(`part-` + UUID). 요청에 담지 않습니다.
                    보관 날짜와 정책은 IRB 승인 전이면 모두 비워 둘 수 있고, 날짜를 하나라도 담으면
                    셋을 모두 담아야 합니다(`identifiedUntil ≤ pseudonymizedAt ≤ researchUntil`).

                    같은 연구·회원에 이미 `ACTIVE` 참여가 있으면 `STUDY_4090`입니다.
                    이름·전화번호는 요청·응답 어디에도 담지 않습니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "등록 성공")
    @ApiResponse(
            responseCode = "400",
            description = "보관 날짜 일부 누락 또는 순서 역전",
            content = @Content(examples = @ExampleObject(value = """
                    {"code": "STUDY_4000", "message": "보존 기간 순서가 올바르지 않습니다.", "data": null}
                    """))
    )
    @ApiResponse(
            responseCode = "409",
            description = "같은 연구·회원의 ACTIVE 참여 중복",
            content = @Content(examples = @ExampleObject(value = """
                    {"code": "STUDY_4090", "message": "이미 등록된 연구 참여입니다.", "data": null}
                    """))
    )
    ApiResponseTemplate<StudyParticipationResponse> register(StudyParticipationCreateRequest request);

    @Operation(summary = "실증 참여 조회", description = "서버 발급 참여 식별자로 현재 동의·보관·철회 상태를 조회합니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(
            responseCode = "404",
            description = "없는 참여 식별자",
            content = @Content(examples = @ExampleObject(value = """
                    {"code": "STUDY_4041", "message": "연구 참여 정보를 찾을 수 없습니다.", "data": null}
                    """))
    )
    ApiResponseTemplate<StudyParticipationResponse> get(String participationId);

    @Operation(
            summary = "보관 계획 수정",
            description = """
                    IRB 승인으로 보관 날짜·정책이 정해지거나 바뀌면 수정합니다. 날짜는 셋 전부이거나 전무이며,
                    바꾼 결과는 **불변 변경 이력**으로 함께 남습니다. 과거 보관 계획은 덮어써도 사라지지 않습니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "수정 성공")
    ApiResponseTemplate<StudyParticipationResponse> changeRetention(
            String participationId, StudyRetentionChangeRequest request);

    @Operation(
            summary = "연구 동의 철회",
            description = """
                    `scope=ALL`은 전체 철회, `SELECTED_CONSENTS`는 `consentKeys`에 담은 항목만 철회합니다.
                    상태가 `WITHDRAWN`으로 바뀌고 철회 시각·범위가 남습니다.

                    **이 API는 자료를 삭제하지 않습니다.** 센서 원문·심박·위치의 삭제는 운영자의 수동 절차이고,
                    마치면 삭제 처리 기록 API로 시각만 남깁니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "철회 처리 성공")
    ApiResponseTemplate<StudyParticipationResponse> withdraw(
            String participationId, StudyWithdrawalRequest request);

    @Operation(
            summary = "수동 삭제 처리 완료 기록",
            description = "철회한 참여의 자료를 운영자가 지운 뒤 처리 시각을 남깁니다. 철회한 참여에만 쓸 수 있습니다."
    )
    @ApiResponse(responseCode = "200", description = "기록 성공")
    ApiResponseTemplate<StudyParticipationResponse> markDeletionProcessed(String participationId);
}
