package com.widyu.incident.controller.docs;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.incident.dto.request.IncidentRespondRequest;
import com.widyu.incident.dto.request.IncidentResolveRequest;
import com.widyu.incident.dto.response.IncidentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;

@Tag(name = "Incident", description = "위급 알림 뒤의 본인확인·사후 판정 API")
public interface IncidentDocs {

    @Operation(
            summary = "본인확인 응답",
            description = """
                    시니어 본인이 위급 알림에 답합니다.

                    - `OK`면 상태가 `OK_CLOSED`, `HELP`면 `ESCALATED`가 됩니다.
                    - 응답 마감(열린 시각 + 45초)을 넘겨 뒤늦게 온 `OK`는 응답만 남기고
                      상태는 `ESCALATED`로 둡니다. 보호자 알림은 이미 나간 뒤입니다.
                    - 이미 답한 사건은 409(`INCIDENT_4090`)입니다.
                    - 본인 사건이 아니면 404(`INCIDENT_4040`) — 존재 여부를 알리지 않습니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "응답 완료")
    ApiResponseTemplate<IncidentResponse> respond(
            @Parameter(description = "사건 식별자(`inc-`로 시작)") String incidentId,
            IncidentRespondRequest request);

    @Operation(
            summary = "사후 판정",
            description = """
                    보호자가 「진짜 위급이었는지」를 남깁니다. 이 값이 실증의 학습 라벨입니다.

                    - `outcome`은 `TRUE_EMERGENCY` · `FALSE_ALARM` · `UNKNOWN` 중 하나입니다.
                    - 119 신고 시각(`emergencyCalledAtMs`)은 신고했을 때만 보냅니다.
                      서버는 신고하지 않고 그 사실만 기록합니다.
                    - 같은 가족의 시니어만 판정할 수 있고, 이미 판정한 사건은 409(`INCIDENT_4091`)입니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "판정 완료")
    ApiResponseTemplate<IncidentResponse> resolve(
            @Parameter(description = "사건 식별자(`inc-`로 시작)") String incidentId,
            IncidentResolveRequest request);

    @Operation(
            summary = "가족 인시던트 목록",
            description = """
                    보호자가 같은 가족 시니어의 사건을 최근순 50건까지 봅니다.

                    `state`로 `OPEN` · `CHECKING` · `OK_CLOSED` · `ESCALATED` · `RESOLVED`를
                    걸러낼 수 있습니다. 값 집합 밖이면 400(`INCIDENT_4000`)입니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "조회 완료")
    ApiResponseTemplate<List<IncidentResponse>> getIncidents(
            @Parameter(description = "시니어 회원 ID") Long seniorId,
            @Parameter(description = "상태 필터(선택)") String state);

    @Operation(
            summary = "내 응답 대기 인시던트",
            description = "시니어 본인이 아직 답하지 않은 사건을 최근순으로 받습니다. 앱이 확인 화면을 띄울 목록입니다."
    )
    @ApiResponse(responseCode = "200", description = "조회 완료")
    ApiResponseTemplate<List<IncidentResponse>> getMyPendingIncidents();
}
