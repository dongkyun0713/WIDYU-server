package com.widyu.incident.controller.docs;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.incident.dto.request.IncidentRespondRequest;
import com.widyu.incident.dto.request.GuardianResponseRequest;
import com.widyu.incident.dto.response.GuardianResponseResult;
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
                    - 원 단말 클릭 시각 `deviceRespondedAtMs`는 선택값입니다. 서버 수신 시각으로 마감을 판단합니다.
                    - 응답 마감(열린 시각 + 60초)과 같은 시각 또는 그 뒤에 온 `OK`는
                      응답만 남기고 `ESCALATED`를 유지합니다.
                    - 마감 안의 심박 `OK`는 보호자에게 S08 일반 안내를 예약하고
                      `okNoticeSentAtMs`에 예약 시각을 기록합니다. 늦은 `OK`에는 안내가 없습니다.
                    - 운영 전환 플래그가 켜져 있으면 보호자 최초 알림은 마감 뒤 사건 스케줄러가 보냅니다.
                    - 이미 답한 사건은 409(`INCIDENT_4090`)입니다.
                    - 본인 사건이 아니면 404(`INCIDENT_4040`) — 존재 여부를 알리지 않습니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "응답 완료")
    ApiResponseTemplate<IncidentResponse> respond(
            @Parameter(description = "사건 식별자(`inc-`로 시작)") String incidentId,
            IncidentRespondRequest request);

    @Operation(summary = "보호자 실제 연락 행동 기록", description = """
            같은 가족의 활성 보호자가 실제 메시지 전송 성공 또는 전화 걸기 시작을 기록합니다.
            `type`은 `MESSAGE_SENT` 또는 `CALL_INITIATED`이며 최초 기록만 수락합니다.
            화면 열람·팝업 확인·provider answered는 반응이 아닙니다.
            잘못된 입력은 400(`REQ_4000`), 비활성 보호자는 403(`AUTH_4030`),
            사건 없음·다른 가족·시니어 요청은 404(`INCIDENT_4040`), 중복 기록은 409(`INCIDENT_4092`)입니다.
            """)
    @ApiResponse(responseCode = "200", description = "첫 보호자 반응 기록 완료")
    @ApiResponse(responseCode = "400", description = "허용되지 않은 반응 종류")
    @ApiResponse(responseCode = "403", description = "비활성 보호자")
    @ApiResponse(responseCode = "404", description = "사건 또는 접근 가능한 가족 없음")
    @ApiResponse(responseCode = "409", description = "이미 기록된 보호자 반응")
    ApiResponseTemplate<GuardianResponseResult> recordGuardianResponse(
            @Parameter(description = "사건 식별자(`inc-`로 시작)") String incidentId,
            GuardianResponseRequest request);

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
