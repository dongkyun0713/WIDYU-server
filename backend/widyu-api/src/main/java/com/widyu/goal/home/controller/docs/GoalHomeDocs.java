package com.widyu.goal.home.controller.docs;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.goal.home.dto.response.FamilyListResponse;
import com.widyu.goal.home.dto.response.GuardianGoalHomeResponse;
import com.widyu.goal.home.dto.response.GuardianGoalStatsResponse;
import com.widyu.goal.home.dto.response.SeniorGoalHomeResponse;
import com.widyu.goal.home.dto.response.SeniorWeeklyGoalStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Goal Home", description = "목표 홈 화면 API")
public interface GoalHomeDocs {

    @Operation(
            summary = "보호자 - 가족 목록 조회",
            description = """
                    보호자가 자신과 연결된 시니어 가족 목록을 조회합니다.

                    **기능:**
                    - 보호자가 초대코드로 연결한 시니어 목록 조회
                    - 각 시니어의 기본 정보 및 연결 정보 반환

                    **권한:**
                    - 보호자 본인만 가능
                    - 시니어는 이 API를 사용할 수 없음

                    **반환 정보:**
                    - 시니어 ID (memberId)
                    - 시니어 이름 (name)
                    - 시니어 프로필 이미지 (profileImage)
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "조회 성공",
                    content = @Content(
                            schema = @Schema(implementation = ApiResponseTemplate.class),
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "code": "GOAL_HOME_2001",
                                              "message": "가족 목록 조회 성공",
                                              "data": {
                                                "families": [
                                                  {
                                                    "memberId": 1,
                                                    "name": "김부모",
                                                    "profileImage": "https://www.widyu.shop/profile/senior.png"
                                                  }
                                                ]
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    ApiResponseTemplate<FamilyListResponse> getFamilyList();

    @Operation(
            summary = "시니어 - 목표 홈 조회",
            description = """
                    시니어 사용자의 목표 홈 화면 정보를 조회합니다.

                    **반환 정보:**
                    - **약 스케줄**: 다음 복용 스케줄 ID, 오늘 복용/예정 횟수, 다음 복용 예정 개수, 다음 알람 시간
                    - **걸음 수**: 오늘 걸음 수 및 목표 걸음 수
                    - **병원 일정**: 가장 가까운 병원 일정 ID, D-day, 일시, 일정명, 주소

                    **권한:**
                    - 시니어 본인만 가능
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "조회 성공",
                    content = @Content(
                            schema = @Schema(implementation = ApiResponseTemplate.class),
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "code": "GOAL_HOME_2002",
                                              "message": "시니어 목표 홈 조회 성공",
                                              "data": {
                                                "medicine": {
                                                  "medicineScheduleId": 1,
                                                  "takenCount": 2,
                                                  "totalCount": 3,
                                                  "nextDoseCount": 4,
                                                  "nextAlarmTime": "17:00",
                                                  "proofImageUrl": "https://www.widyu.shop/img"
                                                },
                                                "steps": {
                                                  "steps": 9829,
                                                  "goal": 10000
                                                },
                                                "hospital": {
                                                  "hospitalScheduleId": 1,
                                                  "dday": 14,
                                                  "datetime": "2025-08-26T17:00:00",
                                                  "name": "고려대학교 의과대학 부속병원",
                                                  "address": "서울특별시 성북구 고려대로 73"
                                                }
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    ApiResponseTemplate<SeniorGoalHomeResponse> getSeniorGoalHome();

    @Operation(
            summary = "시니어 - 이번 주 목표 달성률 조회",
            description = """
                    시니어의 이번 주(일요일~토요일) 목표 달성 상태를 조회합니다.

                    **상태 값:**
                    - **NOT_STARTED**: 기한 전/오늘, 시작 전
                    - **IN_PROGRESS**: 기한 전/오늘, 진행 중 (일부 목표만 완료)
                    - **COMPLETED**: 기한 내 완료 (모든 목표 완료)
                    - **FAILED**: 기한 내 완료 X (기한이 지났지만 미완료 또는 일부만 완료)

                    **참고:**
                    - 약 복용과 걸음 수 목표를 모두 고려하여 상태 계산
                    - 미래 날짜는 NOT_STARTED로 표시
                    - 오늘 날짜에 일부 목표만 완료한 경우 IN_PROGRESS
                    - 과거 날짜에 미완료 또는 일부만 완료한 경우 FAILED

                    **권한:**
                    - 시니어 본인만 가능
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "조회 성공",
                    content = @Content(
                            schema = @Schema(implementation = ApiResponseTemplate.class),
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "code": "GOAL_HOME_2003",
                                              "message": "시니어 주간 목표 달성률 조회 성공",
                                              "data": {
                                                "thisWeekGoalRates": [
                                                  "NOT_STARTED",
                                                  "IN_PROGRESS",
                                                  "COMPLETED",
                                                  "FAILED",
                                                  "NOT_STARTED",
                                                  "NOT_STARTED",
                                                  "NOT_STARTED"
                                                ]
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    ApiResponseTemplate<SeniorWeeklyGoalStatusResponse> getSeniorWeeklyGoalStatus();

    @Operation(
            summary = "보호자 - 목표 현황 조회",
            description = """
                    보호자가 연결된 시니어의 목표 달성 현황을 조회합니다.

                    **반환 정보:**
                    - **지난주 목표 달성률**: 지난주(일~토) 전체 달성률
                    - **오늘 목표 달성률**: 오늘 목표(약 복용·걸음 수) 중 달성한 비율
                    - **이번주 일별 달성률**: 일요일부터 토요일까지 각 날짜의 달성률 배열

                    **달성률 계산:**
                    - 약 복용과 걸음 수 목표를 모두 달성한 날의 비율
                    - 0.0 ~ 1.0 사이의 값 (예: 0.80 = 80%)

                    **권한:**
                    - 보호자가 연결된 시니어의 정보 조회 가능
                    - memberId가 null인 경우 첫 번째 연결된 시니어 자동 선택
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "조회 성공",
                    content = @Content(
                            schema = @Schema(implementation = ApiResponseTemplate.class),
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "code": "GOAL_HOME_2004",
                                              "message": "보호자 목표 현황 조회 성공",
                                              "data": {
                                                "lastWeekGoalRate": 0.8,
                                                "todayGoalRate": 0.65,
                                                "thisWeekGoalRates": [0.6, 0.24, 0.53, 0.75, 0.85, 0.9, 1.0]
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(responseCode = "400", description = "연결된 부모님이 없음"),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    ApiResponseTemplate<GuardianGoalStatsResponse> getGuardianGoalStats(
            @Parameter(description = "시니어(부모님) ID - null일 경우 첫 번째 연결된 부모님 자동 선택", example = "1")
            Long memberId
    );

    @Operation(
            summary = "보호자 - 목표 홈 조회",
            description = """
                    보호자가 연결된 시니어의 목표 홈 상세 정보를 조회합니다.

                    **반환 정보:**
                    - **약 스케줄**: 오늘 복용 현황, 각 스케줄별 상세 정보 (복용 상태, 인증 이미지)
                    - **걸음 수**: 오늘 걸음 수 및 목표
                    - **병원 일정**: 가장 가까운 병원 일정 (D-day, 일시, 병원명, 주소)

                    **약 스케줄 상세:**
                    - 각 알람 시간 (HH:mm 형식, 예: "19:00")
                    - 복용 상태 (DONE: 복용 완료, UPCOMING: 복용 시간 전, MISSED: 놓침)
                    - 복용 인증 사진 URL
                    - 복용해야 할 약 목록 (약 이름, 개수)

                    **권한:**
                    - 보호자가 연결된 시니어의 정보 조회 가능
                    - memberId가 null인 경우 첫 번째 연결된 시니어 자동 선택
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "조회 성공",
                    content = @Content(
                            schema = @Schema(implementation = ApiResponseTemplate.class),
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "code": "GOAL_HOME_2005",
                                              "message": "보호자 목표 홈 조회 성공",
                                              "data": {
                                                "medicine": {
                                                  "totalCount": 6,
                                                  "takenCount": 1,
                                                  "medicineSchedules": [
                                                    {
                                                      "medicineScheduleId": 101,
                                                      "alarmTime": "19:00",
                                                      "status": "DONE",
                                                      "proofImageUrl": "https://www.widyu.shop/img",
                                                      "medicines": [
                                                        {
                                                          "name": "위염약",
                                                          "count": 5
                                                        }
                                                      ]
                                                    }
                                                  ]
                                                },
                                                "steps": {
                                                  "steps": 9829,
                                                  "goal": 10000
                                                },
                                                "hospital": {
                                                  "hospitalScheduleId": 1,
                                                  "dday": 14,
                                                  "datetime": "2025-08-26T17:00:00",
                                                  "name": "고려대학교 의과대학 부속병원",
                                                  "address": "서울특별시 성북구 고려대로 73"
                                                }
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(responseCode = "400", description = "연결된 부모님이 없음"),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    ApiResponseTemplate<GuardianGoalHomeResponse> getGuardianGoalHome(
            @Parameter(description = "시니어(부모님) ID - null일 경우 첫 번째 연결된 부모님 자동 선택", example = "1")
            Long memberId
    );
}
