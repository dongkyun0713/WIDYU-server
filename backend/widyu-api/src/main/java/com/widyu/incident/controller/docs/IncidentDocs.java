package com.widyu.incident.controller.docs;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.incident.dto.request.IncidentRespondRequest;
import com.widyu.incident.dto.request.HelpRequestCreateRequest;
import com.widyu.incident.dto.request.GuardianResponseRequest;
import com.widyu.incident.dto.response.GuardianResponseResult;
import com.widyu.incident.dto.request.IncidentResolveRequest;
import com.widyu.incident.dto.response.IncidentResponse;
import com.widyu.incident.dto.response.IncidentStatusResponse;
import com.widyu.incident.dto.response.HelpRequestResult;
import com.widyu.incident.dto.response.HelpRequestCancelResult;
import com.widyu.incident.dto.request.PromptDisplayedRequest;
import com.widyu.incident.dto.response.PromptDisplayedResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;

@Tag(name = "Incident", description = "위급 알림 뒤의 본인확인·사후 판정 API")
public interface IncidentDocs {

    @Operation(summary = "워치 도움 요청", description = """
            시니어가 워치에서 직접 도움을 요청합니다. 본문은 생략하거나 via=WATCH와
            deviceRequestedAtMs를 보낼 수 있습니다. 열린 심박 사건에는 HELP로 부착하고
            이미 답한 사건이면 첫 응답을 보존하며 시도 이력을 남깁니다. 열린 도움 요청은
            같은 사건을 200으로 반환하고 ①을 다시 보내지 않습니다.
            """)
    @ApiResponse(responseCode = "200", description = "사건 접수 또는 멱등 부착, INCIDENT_2007")
    @ApiResponse(responseCode = "400", description = "잘못된 via 또는 본문")
    @ApiResponse(responseCode = "403", description = "시니어 계정 아님")
    ApiResponseTemplate<HelpRequestResult> openHelpRequest(HelpRequestCreateRequest request);

    @Operation(summary = "워치 도움 요청 취소", description = """
            자기 자신의 열린 SELF_HELP_REQUEST만 취소합니다. 미발송 ②를 막고 ①·②의
            실제 센터 수신자에게 취소 안내를 한 번 예약합니다. 심박 OK는 기존 응답 API를 사용합니다.
            """)
    @ApiResponse(responseCode = "200", description = "취소 완료, INCIDENT_2008")
    @ApiResponse(responseCode = "403", description = "시니어 계정 아님")
    @ApiResponse(responseCode = "404", description = "사건 없음 또는 다른 시니어 소유")
    @ApiResponse(responseCode = "409", description = "열린 도움 요청 아님, INCIDENT_4093")
    ApiResponseTemplate<HelpRequestCancelResult> cancelHelpRequest(String incidentRef);

    @Operation(summary = "본인확인 화면 수신 완료", description = """
            시니어 앱(폰·워치)이 심박 위급 확인 화면을 띄우는 즉시 호출합니다.

            - 마감 전 첫 호출이면 응답 마감을 서버 수신 시각 + 60초로 옮기고 `deadlineExtended=true`입니다.
              앱은 응답의 `respondByMs`로 남은 시간을 표시합니다.
            - 이 호출이 열린 시각 + 90초 안에 없으면 화면을 받지 못한 것으로 보고 무응답 처리합니다.
            - 두 번째 호출(다른 기기 포함)·마감 뒤·응답 뒤·심박 외 사건은 값을 바꾸지 않고
              `deadlineExtended=false`와 현재 값을 돌려줍니다. 다시 보내도 안전합니다.
            - 본인 사건이 아니면 404(`INCIDENT_4040`)입니다.
            """)
    @ApiResponse(responseCode = "200", description = "수신 기록, INCIDENT_2009")
    @ApiResponse(responseCode = "400", description = "via 누락 또는 잘못된 값")
    @ApiResponse(responseCode = "404", description = "사건 없음 또는 다른 시니어 소유")
    ApiResponseTemplate<PromptDisplayedResult> markPromptDisplayed(String incidentId, PromptDisplayedRequest request);

    @Operation(
            summary = "본인확인 응답",
            description = """
                    시니어 본인이 위급 알림에 답합니다.

                    - `OK`면 상태가 `OK_CLOSED`, `HELP`면 `ESCALATED`가 됩니다.
                    - 원 단말 클릭 시각 `deviceRespondedAtMs`는 선택값입니다. 서버 수신 시각으로 마감을 판단합니다.
                    - 심박 응답 마감은 확인 화면 수신 전 열린 시각 + 90초, 수신 뒤 수신 시각 + 60초입니다
                      (낙상은 열린 시각 + 60초). 마감과 같은 시각 또는 그 뒤에 온 `OK`는
                      응답만 남기고 `ESCALATED`를 유지합니다.
                    - 마감 안의 심박 `OK`는 보호자에게 S08 일반 안내를 예약하고
                      `okNoticeSentAtMs`에 예약 시각을 기록합니다. 늦은 `OK`에는 안내가 없습니다.
                    - 운영 전환 플래그가 켜져 있으면 보호자 최초 알림은 마감 뒤 사건 스케줄러가 보냅니다.
                    - 유효한 본인 사건의 모든 시도를 응답값·기기·단말/서버 시각과
                      `ACCEPTED|LATE|DUPLICATE` 결과로 저장합니다. 늦은 첫 응답도 기록합니다.
                    - 이미 답한 사건은 시도를 기록하고 409(`INCIDENT_4090`)입니다.
                    - 본인 사건이 아니면 404(`INCIDENT_4040`) — 존재 여부를 알리지 않습니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "응답 완료")
    @ApiResponse(responseCode = "409", description = "이미 답한 사건, 시도 이력은 저장")
    @ApiResponse(responseCode = "404", description = "사건 없음 또는 본인 사건 아님")
    ApiResponseTemplate<IncidentResponse> respond(
            @Parameter(description = "사건 식별자(`inc-`로 시작)") String incidentId,
            IncidentRespondRequest request);

    @Operation(summary = "보호자 멈춤 기록", description = """
            같은 가족의 활성 보호자가 `type=ACKNOWLEDGED|MESSAGE_SENT|CALL_INITIATED`를 기록합니다.
            사건×보호자×종류당 한 건이며 같은 종류를 다시 보내면 원래 기록 시각으로 200을 반환합니다.
            한 건이라도 기록되면 진행 중인 상황의 미발송 2차 알림을 취소하고 응답에 취소 여부를 담습니다.
            이미 끝난 사건에 늦게 도착한 확인도 활성 보호자라면 200으로 저장하되 2차 알림은 취소하지 않고
            `incidentEnded=true`를 반환합니다. S12 화면의 확인은 이 API를 호출하지 않습니다.
            화면 열람·푸시 탭·읽음·메시지 작성 화면만 연 것은 멈춤이 아닙니다.
            잘못된 입력은 400(`REQ_4000`), 사건 없음·다른 가족·시니어 요청은 먼저
            404(`INCIDENT_4040`), 같은 가족의 비활성 보호자는 403(`AUTH_4030`)입니다.
            """)
    @ApiResponse(responseCode = "200", description = "보호자 멈춤 기록 또는 같은 종류의 멱등 재호출")
    @ApiResponse(responseCode = "400", description = "허용되지 않은 반응 종류")
    @ApiResponse(responseCode = "403", description = "비활성 보호자")
    @ApiResponse(responseCode = "404", description = "사건 또는 접근 가능한 가족 없음")
    ApiResponseTemplate<GuardianResponseResult> recordGuardianResponse(
            @Parameter(description = "사건 식별자(`inc-`로 시작)") String incidentId,
            GuardianResponseRequest request);

    @Operation(summary = "보호자 상황 상태 조회", description = """
            알림의 사건이 이어지는지 조회합니다. `status=OPEN|ENDED`는 저장된 종료 시각,
            OK_CLOSED/RESOLVED/CANCELLED_BY_SENIOR 상태 또는 심박·낙상의 마지막 감지 뒤
            5분 경과로 계산하며 DB를 갱신하지 않습니다. 열린 워치 도움 요청은 5분만으로 종료되지 않습니다.
            `endedAtMs`와 `endedReason`은 OPEN일 때 null입니다. `state`는 DB 원래 값이고
            `myResponse`에는 요청 보호자 자신의 멈춤 종류만 담깁니다. 다른 보호자의 멈춤은
            종료 조건이 아닙니다. 사건 없음·타 가족·시니어 요청은 404(`INCIDENT_4040`),
            같은 가족의 비활성 보호자는 403(`AUTH_4030`)입니다.
            """)
    @ApiResponse(responseCode = "200", description = "상황 상태 조회 완료, INCIDENT_2006")
    @ApiResponse(responseCode = "403", description = "같은 가족의 비활성 보호자")
    @ApiResponse(responseCode = "404", description = "사건 없음 또는 접근 가능한 가족 없음")
    ApiResponseTemplate<IncidentStatusResponse> getStatus(
            @Parameter(description = "사건 식별자(`inc-`로 시작)") String incidentRef);

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

                    `state`로 `OPEN` · `CHECKING` · `OK_CLOSED` · `ESCALATED` · `RESOLVED` ·
                    `CANCELLED_BY_SENIOR`를
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
