package com.widyu.followup.controller.docs;

import com.widyu.followup.dto.request.FollowupAnswerRequest;
import com.widyu.followup.dto.request.FollowupDeclineRequest;
import com.widyu.followup.dto.response.CurrentFollowupResponse;
import com.widyu.followup.dto.response.FollowupSubmissionResponse;
import com.widyu.global.response.ApiResponseTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Followup", description = "종료 뒤 시니어 질문 카드")
public interface FollowupDocs {
    @Operation(summary = "현재 방문의 후속 카드", description = "시니어 본인만 조회합니다. 방문 키는 UUID이며 방문당 카드 하나만 노출합니다. 카드가 없으면 data.card=null입니다. 기능 OFF·타 역할은 404(FOLLOWUP_4040)입니다.")
    @ApiResponse(responseCode = "200", description = "FOLLOWUP_2001 현재 카드 조회")
    @ApiResponse(responseCode = "400", description = "REQ_4000 요청 형식 오류(방문 키 누락) / FOLLOWUP_4000 빈 값·UUID 형식 오류")
    @ApiResponse(responseCode = "404", description = "FOLLOWUP_4040 카드 기능 비활성 또는 접근 불가")
    ApiResponseTemplate<CurrentFollowupResponse> current(String visitKey);

    @Operation(summary = "후속 질문 원답 제출", description = "첫 제출만 저장합니다. Q1/Q2에는 모름·거절 값도 보존합니다. 만료 전 단말 제출의 늦은 서버 접수는 허용합니다.")
    @ApiResponse(responseCode = "200", description = "FOLLOWUP_2002 첫 답변 저장")
    @ApiResponse(responseCode = "400", description = "REQ_4000 요청 형식 오류(JSON·enum 리터럴) / FOLLOWUP_4000 판본 불일치·필수 원답 누락·Q3 조합·제출 시각 오류")
    @ApiResponse(responseCode = "404", description = "FOLLOWUP_4040 카드 없음·타인·기능 OFF")
    @ApiResponse(responseCode = "409", description = "FOLLOWUP_4090 이미 제출")
    @ApiResponse(responseCode = "410", description = "FOLLOWUP_4100 제출 기한 경과")
    ApiResponseTemplate<FollowupSubmissionResponse> answer(Long id, FollowupAnswerRequest request);

    @Operation(summary = "후속 질문 전체 건너뛰기", description = "명시적인 건너뛰기를 DECLINED로 기록하고 단말·서버 시각을 남깁니다.")
    @ApiResponse(responseCode = "200", description = "FOLLOWUP_2003 첫 거절 저장")
    @ApiResponse(responseCode = "400", description = "REQ_4000 요청 형식 오류 / FOLLOWUP_4000 제출 시각 누락·0 이하")
    @ApiResponse(responseCode = "404", description = "FOLLOWUP_4040 카드 없음·타인·기능 OFF")
    @ApiResponse(responseCode = "409", description = "FOLLOWUP_4090 이미 제출")
    @ApiResponse(responseCode = "410", description = "FOLLOWUP_4100 제출 기한 경과")
    ApiResponseTemplate<FollowupSubmissionResponse> decline(Long id, FollowupDeclineRequest request);
}
