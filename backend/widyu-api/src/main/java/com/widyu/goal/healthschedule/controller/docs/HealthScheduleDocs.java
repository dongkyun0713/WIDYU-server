package com.widyu.goal.healthschedule.controller.docs;

import com.widyu.goal.healthschedule.dto.request.HealthScheduleCompleteRequest;
import com.widyu.goal.healthschedule.dto.request.HealthScheduleCreateForSeniorRequest;
import com.widyu.goal.healthschedule.dto.request.HealthScheduleCreateRequest;
import com.widyu.goal.healthschedule.dto.request.HealthScheduleUpdateRequest;
import com.widyu.goal.healthschedule.dto.response.HealthScheduleDayResponse;
import com.widyu.goal.healthschedule.dto.response.HealthScheduleDetailResponse;
import com.widyu.goal.healthschedule.dto.response.HealthScheduleDetailWithRewardResponse;
import com.widyu.goal.healthschedule.dto.response.HealthScheduleResponse;
import com.widyu.goal.healthschedule.dto.response.HealthScheduleWeekListResponse;
import com.widyu.global.response.ApiResponseTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Health-Schedule", description = "건강 일정 관리 API")
public interface HealthScheduleDocs {

    @Operation(
            summary = "시니어 본인 건강 일정 생성",
            description = "시니어가 자신의 건강 일정을 생성합니다. 생성 시 rewardPoint는 100, isReward는 false로 자동 설정됩니다."
    )
    @RequestBody(
            description = "건강 일정 생성 정보",
            required = true,
            content = @Content(
                    schema = @Schema(implementation = HealthScheduleCreateRequest.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "scheduleName": "정기 건강검진",
                                      "placeAddress": "서울대학교병원",
                                      "latitude": 37.5665,
                                      "longitude": 126.9780,
                                      "scheduledAt": "2025-11-15T14:30:00"
                                    }
                                    """
                    )
            )
    )
    @ApiResponse(
            responseCode = "200",
            description = "건강 일정 생성 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "HLTH_2001",
                                      "message": "건강 일정이 생성되었습니다.",
                                      "data": {
                                        "scheduleName": "정기 건강검진",
                                        "scheduledAt": "2025-11-15T14:30:00",
                                        "placeAddress": "서울대학교병원",
                                        "latitude": 37.5665,
                                        "longitude": 126.9780
                                      }
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<HealthScheduleResponse> createHealthScheduleForMe(HealthScheduleCreateRequest request);

    @Operation(
            summary = "보호자가 시니어 건강 일정 생성",
            description = "보호자가 연결된 시니어의 건강 일정을 생성합니다. FamilyMembership 검증을 통해 권한을 확인합니다."
    )
    @RequestBody(
            description = "건강 일정 생성 정보",
            required = true,
            content = @Content(
                    schema = @Schema(implementation = HealthScheduleCreateForSeniorRequest.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "memberId": 1,
                                      "scheduleName": "정기 건강검진",
                                      "placeAddress": "서울대학교병원",
                                      "latitude": 37.5665,
                                      "longitude": 126.9780,
                                      "scheduledAt": "2025-11-15T14:30:00"
                                    }
                                    """
                    )
            )
    )
    @ApiResponse(
            responseCode = "200",
            description = "건강 일정 생성 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "HLTH_2001",
                                      "message": "건강 일정이 생성되었습니다.",
                                      "data": {
                                        "scheduleName": "정기 건강검진",
                                        "scheduledAt": "2025-11-15T14:30:00",
                                        "placeAddress": "서울대학교병원",
                                        "latitude": 37.5665,
                                        "longitude": 126.9780
                                      }
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<HealthScheduleResponse> createHealthScheduleForSenior(
            HealthScheduleCreateForSeniorRequest request
    );

    @Operation(
            summary = "건강 일정 수정",
            description = "건강 일정을 수정합니다. 시니어는 본인의 일정만, 보호자는 연결된 시니어의 일정만 수정 가능합니다."
    )
    @Parameter(name = "healthScheduleId", description = "건강 일정 ID", required = true, example = "1")
    @RequestBody(
            description = "건강 일정 수정 정보",
            required = true,
            content = @Content(
                    schema = @Schema(implementation = HealthScheduleUpdateRequest.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "scheduleName": "정기 건강검진 (수정)",
                                      "placeAddress": "강남세브란스병원",
                                      "latitude": 37.5665,
                                      "longitude": 126.9780,
                                      "scheduledAt": "2025-11-20T10:00:00",
                                      "progressStatus": "UPCOMING"
                                    }
                                    """
                    )
            )
    )
    @ApiResponse(
            responseCode = "200",
            description = "건강 일정 수정 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "HLTH_2002",
                                      "message": "건강 일정이 수정되었습니다.",
                                      "data": {
                                        "scheduleName": "정기 건강검진 (수정)",
                                        "scheduledAt": "2025-11-20T10:00:00",
                                        "placeAddress": "강남세브란스병원",
                                        "latitude": 37.5665,
                                        "longitude": 126.9780
                                      }
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<HealthScheduleResponse> updateHealthSchedule(
            Long healthScheduleId,
            HealthScheduleUpdateRequest request
    );

    @Operation(
            summary = "건강 일정 삭제 (논리 삭제)",
            description = "건강 일정을 논리 삭제합니다. 시니어는 본인의 일정만, 보호자는 연결된 시니어의 일정만 삭제 가능합니다."
    )
    @Parameter(name = "healthScheduleId", description = "건강 일정 ID", required = true, example = "1")
    @ApiResponse(
            responseCode = "200",
            description = "건강 일정 삭제 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "HLTH_2003",
                                      "message": "건강 일정이 삭제되었습니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<Void> deleteHealthSchedule(Long healthScheduleId);

    @Operation(
            summary = "시니어 본인 건강 일정 캘린더 조회",
            description = "시니어가 특정 년월의 건강 일정 캘린더를 조회합니다. 해당 월에 일정이 있는 날짜와 진행 상태를 반환합니다."
    )
    @Parameter(name = "year", description = "조회 연도", required = true, example = "2025")
    @Parameter(name = "month", description = "조회 월", required = true, example = "11")
    @ApiResponse(
            responseCode = "200",
            description = "건강 일정 캘린더 조회 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "HLTH_2004",
                                      "message": "건강 일정 캘린더 조회가 완료되었습니다.",
                                      "data": [
                                        {
                                          "day": "2025-11-15",
                                          "progressStatus": "UPCOMING"
                                        },
                                        {
                                          "day": "2025-11-20",
                                          "progressStatus": "COMPLETED"
                                        }
                                      ]
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<List<HealthScheduleDayResponse>> getHealthScheduleCalendarForMe(int year, int month);

    @Operation(
            summary = "보호자가 시니어 건강 일정 캘린더 조회",
            description = "보호자가 연결된 시니어의 특정 년월 건강 일정 캘린더를 조회합니다."
    )
    @Parameter(name = "memberId", description = "시니어 회원 ID", required = true, example = "123")
    @Parameter(name = "year", description = "조회 연도", required = true, example = "2025")
    @Parameter(name = "month", description = "조회 월", required = true, example = "11")
    @ApiResponse(
            responseCode = "200",
            description = "건강 일정 캘린더 조회 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "HLTH_2004",
                                      "message": "건강 일정 캘린더 조회가 완료되었습니다.",
                                      "data": [
                                        {
                                          "day": "2025-11-15",
                                          "progressStatus": "UPCOMING"
                                        },
                                        {
                                          "day": "2025-11-20",
                                          "progressStatus": "COMPLETED"
                                        }
                                      ]
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<List<HealthScheduleDayResponse>> getHealthScheduleCalendarForSenior(
            Long memberId,
            int year,
            int month
    );

    @Operation(
            summary = "시니어 본인 특정 날짜 건강 일정 상세 조회",
            description = "시니어가 특정 날짜의 건강 일정 상세 정보를 조회합니다. rewardPoint와 isReward 정보가 포함됩니다."
    )
    @Parameter(name = "date", description = "조회 날짜 (yyyy-MM-dd)", required = true, example = "2025-11-15")
    @ApiResponse(
            responseCode = "200",
            description = "건강 일정 상세 조회 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "HLTH_2005",
                                      "message": "건강 일정 상세 조회가 완료되었습니다.",
                                      "data": [
                                        {
                                          "healthScheduleId": 1,
                                          "scheduleName": "병원 진료",
                                          "scheduledAt": "2025-11-15T14:30:00",
                                          "placeAddress": "서울대학교병원",
                                          "latitude": 37.5665,
                                          "longitude": 126.9780,
                                          "progressStatus": "COMPLETED",
                                          "rewardPoint": 100,
                                          "isReward": true
                                        }
                                      ]
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<List<HealthScheduleDetailWithRewardResponse>> getHealthSchedulesByDateForMe(LocalDate date);

    @Operation(
            summary = "보호자가 시니어 특정 날짜 건강 일정 상세 조회",
            description = "보호자가 연결된 시니어의 특정 날짜 건강 일정 상세 정보를 조회합니다. 보상 정보는 포함되지 않습니다."
    )
    @Parameter(name = "memberId", description = "시니어 회원 ID", required = true, example = "123")
    @Parameter(name = "date", description = "조회 날짜 (yyyy-MM-dd)", required = true, example = "2025-11-15")
    @ApiResponse(
            responseCode = "200",
            description = "건강 일정 상세 조회 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "HLTH_2005",
                                      "message": "건강 일정 상세 조회가 완료되었습니다.",
                                      "data": [
                                        {
                                          "healthScheduleId": 1,
                                          "scheduleName": "병원 진료",
                                          "scheduledAt": "2025-11-15T14:30:00",
                                          "placeAddress": "서울대학교병원",
                                          "latitude": 37.5665,
                                          "longitude": 126.9780,
                                          "progressStatus": "COMPLETED"
                                        }
                                      ]
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<List<HealthScheduleDetailResponse>> getHealthSchedulesByDateForSenior(
            Long memberId,
            LocalDate date
    );

    @Operation(
            summary = "일주일치 건강 일정 조회 (로그인 시)",
            description = "시니어가 로그인 시 오늘부터 7일간의 건강 일정을 조회합니다. 병원 위치 정보가 포함됩니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "일주일치 건강 일정 조회 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "HLTH_2007",
                                      "message": "일주일치 건강 일정 조회가 완료되었습니다.",
                                      "data": {
                                        "schedules": [
                                          {
                                            "datetime": "2025-11-01T14:30:00",
                                            "healthScheduleId": 1,
                                            "latitude": 37.5665,
                                            "longitude": 126.9780
                                          },
                                          {
                                            "datetime": "2025-11-05T10:00:00",
                                            "healthScheduleId": 2,
                                            "latitude": 37.5665,
                                            "longitude": 126.9780
                                          }
                                        ]
                                      }
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<HealthScheduleWeekListResponse> getHealthSchedulesForWeek();

    @Operation(
            summary = "건강 일정 완료 처리",
            description = """
                    건강 일정의 progressStatus를 COMPLETED로 변경합니다.
                    시니어는 본인의 일정만, 보호자는 연결된 시니어의 일정만 완료 처리 가능합니다.
                    방문 인증은 일정 당일 00시부터 일정 시간 30분 후까지 가능합니다.
                    클라이언트 좌표를 별도로 받지 않고, 서버에 저장된 최신 시니어 위치가 일정 장소 반경 75m 이내인지 검증합니다.
                    최초 완료 시 rewardPoint가 1회 적립되며, 재완료 처리해도 중복 적립되지 않습니다.
                    """
    )
    @RequestBody(
            description = "완료 처리할 건강 일정 정보",
            required = true,
            content = @Content(
                    schema = @Schema(implementation = HealthScheduleCompleteRequest.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "healthScheduleId": 1
                                    }
                                    """
                    )
            )
    )
    @ApiResponse(
            responseCode = "200",
            description = "건강 일정 완료 처리 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "HLTH_2008",
                                      "message": "건강 일정이 완료 처리되었습니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<Void> completeSchedule(HealthScheduleCompleteRequest request);
}
