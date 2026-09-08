package com.widyu.heart.controller.docs;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.heart.dto.request.HeartMessageRequest;
import com.widyu.heart.dto.response.HeartGraphPageResponse;
import com.widyu.heart.dto.response.HeartRateStatusResponse;
import com.widyu.heart.dto.response.RecentEmergencyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;

@Tag(name = "Heart Rate", description = "심박수 관련 API")
public interface HeartRateDocs {

    @Operation(
            summary = "심박수 이상치 조회",
            description = """
                    회원의 가장 최신 심박수 분석 결과를 조회합니다.

                    **조회 우선순위**:
                    1. 최신 분석 결과 (Redis, 24시간 TTL)
                    2. 분석 결과가 만료·부재 시: 가장 최근 심박수 이벤트(DB) 값으로 대체 반환
                    3. 이벤트도 없으면 `UNKNOWN` 반환

                    **접근 권한**:
                    - memberId 미입력 시: 본인의 심박수 조회
                    - memberId 입력 시: 가족 관계가 있는 경우에만 조회 가능

                    **HeartRateStatus 상태값**:
                    - `NORMAL`: 정상
                    - `CAUTION`: 주의
                    - `EMERGENCY`: 긴급
                    - `ANOMALY`: 기존 이상 상태 데이터
                    - `UNKNOWN`: 판별 불가 (데이터 없음)
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "조회 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = {
                            @ExampleObject(
                                    name = "정상 심박수",
                                    value = """
                                            {
                                              "code": "HEART_2001",
                                              "message": "심박수 이상치 조회 완료",
                                              "data": {
                                                "memberId": 1023,
                                                "heartRateStatus": "NORMAL",
                                                "heartRate": 82,
                                                "measuredAt": "2026-02-01T15:48:00"
                                              }
                                            }
                                            """
                            ),
                            @ExampleObject(
                                    name = "긴급 심박수",
                                    value = """
                                            {
                                              "code": "HEART_2001",
                                              "message": "심박수 이상치 조회 완료",
                                              "data": {
                                                "memberId": 1023,
                                                "heartRateStatus": "EMERGENCY",
                                                "heartRate": 180,
                                                "measuredAt": "2026-02-01T15:48:00"
                                              }
                                            }
                                            """
                            ),
                            @ExampleObject(
                                    name = "데이터 없음",
                                    value = """
                                            {
                                              "code": "HEART_2001",
                                              "message": "심박수 이상치 조회 완료",
                                              "data": {
                                                "memberId": 1023,
                                                "heartRateStatus": "UNKNOWN",
                                                "heartRate": null,
                                                "measuredAt": null
                                              }
                                            }
                                            """
                            )
                    }
            )
    )
    @ApiResponse(
            responseCode = "403",
            description = "권한 없음",
            content = @Content(
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "AUTH_4030",
                                      "message": "접근 권한이 없습니다. - 가족으로 연결된 시니어만 접근할 수 있습니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<HeartRateStatusResponse> getHeartRateStatus(
            @Parameter(description = "조회할 회원 ID (미입력 시 본인)", example = "1023")
            Long memberId
    );

    @Operation(
            summary = "최근 15분 내 위험 심박수 감지 여부",
            description = """
                    최근 15분 이내에 위급상황(EMERGENCY)으로 기록된 심박수가 있는지 여부만 반환합니다.
                    보호자에게 FCM 긴급 알림이 발송되는 것과 동일한 기준(`alert=true` + `EMERGENCY`)입니다.

                    **접근 권한**:
                    - memberId 미입력 시: 본인 조회
                    - memberId 입력 시: 가족 연결된 경우에만 조회 가능
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "조회 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = {
                            @ExampleObject(
                                    name = "감지됨",
                                    value = """
                                            {
                                              "code": "HEART_2006",
                                              "message": "최근 심박수 위험 감지 여부 조회 완료",
                                              "data": {
                                                "detected": true
                                              }
                                            }
                                            """
                            ),
                            @ExampleObject(
                                    name = "감지 안 됨",
                                    value = """
                                            {
                                              "code": "HEART_2006",
                                              "message": "최근 심박수 위험 감지 여부 조회 완료",
                                              "data": {
                                                "detected": false
                                              }
                                            }
                                            """
                            )
                    }
            )
    )
    ApiResponseTemplate<RecentEmergencyResponse> getRecentEmergency(
            @Parameter(description = "조회할 회원 ID (미입력 시 본인)", example = "1023")
            Long memberId
    );

    @Operation(
            summary = "심박수 그래프 최초 조회 (응급 화면)",
            description = """
                    응급 상황 심박수 그래프 화면 최초 진입 시 호출합니다.

                    **조회 범위는 진행 중인 위급 사이클입니다.** 사이클 시작(첫 위험 감지) **5분 전**부터
                    현재까지의 이벤트를 반환합니다. 이상해지기 직전의 정상 구간을 함께 보여주기 위한 여유입니다.
                    사이클 판정은 `/emergency/recent`와 동일한 5분 연장 규칙을 따릅니다.

                    **진행 중인 사이클이 없으면 `events`는 빈 배열이고 `firstEmergency`·최대/최소는 null입니다.**
                    이 경우에도 `current`의 현재 심박수와 상태는 채워집니다.

                    `heartGraph`의 이벤트·최대/최소·최초 이상치는 모두 사이클 범위 기준입니다.
                    `emergencyHistory`의 `emergencyCount`·`events`는 기간 제한 없는 전체 위급상황 이력이라
                    사이클과 무관하게 항상 반환됩니다.
                    `emergencyHistory.totalDuration`은 **진행 중인 사이클의 지속 시간(분)** 으로,
                    첫 감지부터 마지막 감지까지의 간격입니다. 단발 감지이거나 사이클이 없으면 0입니다.

                    **접근 권한**:
                    - memberId 미입력 시: 본인 조회
                    - memberId 입력 시: 가족 연결된 경우에만 조회 가능
                    """
    )
    @ApiResponse(responseCode = "200", description = "조회 성공")
    ApiResponseTemplate<HeartGraphPageResponse> getHeartGraph(
            @Parameter(description = "조회할 회원 ID (미입력 시 본인)", example = "1023")
            Long memberId
    );

    @Operation(
            summary = "심박수 그래프 갱신",
            description = """
                    심박수 그래프를 갱신할 때 호출합니다. 초당 폴링에 사용할 수 있습니다.

                    **`since` 사용법 (권장)**:
                    직전 응답에서 받은 마지막 이벤트의 `measuredAt`을 `since`로 넘기면 그 이후 신규 이벤트만 반환합니다.
                    워치가 1초마다 단건으로 전송하므로, `since` 이후의 신규 측정값만 이어서 받을 수 있습니다.
                    신규 이벤트가 없으면 `events`는 빈 배열이며 `current`는 항상 채워집니다.

                    `since` 미입력 시에는 기존 동작대로 최근 5개 이벤트를 반환합니다.
                    `since`가 사이클 조회 범위(사이클 시작 5분 전)보다 과거이면 그 시작점으로 잘라냅니다.

                    **진행 중인 위급 사이클이 없으면 `events`는 빈 배열입니다.** 최초 조회와 동일한 규칙입니다.

                    `current.measuredAt`은 갱신 응답에도 포함됩니다.

                    **접근 권한**:
                    - memberId 미입력 시: 본인 조회
                    - memberId 입력 시: 가족 연결된 경우에만 조회 가능
                    """
    )
    @ApiResponse(responseCode = "200", description = "갱신 성공")
    ApiResponseTemplate<HeartGraphPageResponse> getHeartGraphRefresh(
            @Parameter(description = "조회할 회원 ID (미입력 시 본인)", example = "1023")
            Long memberId,
            @Parameter(description = "이 시각 이후의 신규 이벤트만 조회 (미입력 시 최근 5개)", example = "2026-02-01T15:48:00")
            LocalDateTime since
    );

    @Operation(
            summary = "가족 메시지 전송",
            description = """
                    같은 가족에게 50자 이내의 메시지를 FCM 알림으로 전송합니다.
                    알림은 수신자의 알림 내역에 저장됩니다.

                    **접근 권한**:
                    - 보호자 → 부모님, 부모님 → 보호자 방향 모두 가능
                    - 반드시 가족 연결이 되어 있어야 합니다.
                    """
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "메시지 전송 요청",
            required = true,
            content = @Content(
                    schema = @Schema(implementation = HeartMessageRequest.class),
                    examples = @ExampleObject(
                            name = "메시지 전송 예시",
                            value = """
                                    {
                                      "receiverId": 1001,
                                      "message": "오늘 건강은 좀 어때요?"
                                    }
                                    """
                    )
            )
    )
    @ApiResponse(
            responseCode = "200",
            description = "전송 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "HEART_2003",
                                      "message": "메시지 전송 완료",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    @ApiResponse(
            responseCode = "400",
            description = "잘못된 요청",
            content = @Content(
                    examples = {
                            @ExampleObject(
                                    name = "메시지 초과",
                                    value = """
                                            {
                                              "code": "REQ_4000",
                                              "message": "메시지는 50자 이내로 입력해주세요.",
                                              "data": null
                                            }
                                            """
                            ),
                            @ExampleObject(
                                    name = "필수값 누락",
                                    value = """
                                            {
                                              "code": "REQ_4000",
                                              "message": "받는 사람 ID는 필수입니다.",
                                              "data": null
                                            }
                                            """
                            )
                    }
            )
    )
    @ApiResponse(
            responseCode = "403",
            description = "가족 관계 없음",
            content = @Content(
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "AUTH_4030",
                                      "message": "접근 권한이 없습니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    @ApiResponse(
            responseCode = "404",
            description = "회원 없음",
            content = @Content(
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "MEMBER_4041",
                                      "message": "회원을 찾을 수 없습니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<Void> sendHeartMessage(HeartMessageRequest request);
}
