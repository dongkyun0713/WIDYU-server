package com.widyu.incident.controller.docs;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.incident.dto.request.DevHeartEmergencyRequest;
import com.widyu.incident.dto.response.IncidentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Dev Incident", description = "개발 서버 전용 위급 알림 테스트 API (운영 미등록)")
public interface DevIncidentDocs {

    @Operation(summary = "심박 위급 강제 발생 (개발 전용)", description = """
            프론트 테스트를 위해 실제 심박 위급 판정 없이 사건을 엽니다. 개발·로컬 서버에만 있습니다.

            - 시니어 토큰: 본문 없이 호출하면 본인 사건을 엽니다.
            - 보호자 토큰: `{"seniorId": 12}`로 같은 가족 시니어를 지정합니다. 다른 가족이면 403입니다.
            - 이후 흐름은 실제와 같습니다. 시니어에게 확인 푸시(`data.notificationType=SAFETY_SELF_CHECK`)가 가고,
              `self-check-first` 설정에 따라 방장 1차 알림이 바로 또는 마감 뒤에 나가며, 3분 뒤 2차 알림이 이어집니다.
            - 연달아 호출해도 매번 새 사건이 열립니다. 열려 있던 심박 사건은 상황을 종료 처리해 2차 알림이 나가지 않습니다.
            - 응답의 `incidentId`로 확인 화면 수신 완료·본인확인 응답 API를 시험할 수 있습니다.
            """)
    @ApiResponse(responseCode = "200", description = "사건 생성, INCIDENT_2010")
    @ApiResponse(responseCode = "400", description = "보호자인데 seniorId 없음")
    @ApiResponse(responseCode = "403", description = "같은 가족이 아니거나 비활성 회원")
    ApiResponseTemplate<IncidentResponse> triggerHeartEmergency(DevHeartEmergencyRequest request);
}
