package com.widyu.goal.walk.controller.docs;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.goal.walk.dto.request.SetGoalRequest;
import com.widyu.goal.walk.dto.request.UpdateStepsRequest;
import com.widyu.goal.walk.dto.response.UpdateStepsResponse;
import com.widyu.goal.walk.dto.response.UpcomingGoalResponse;
import com.widyu.goal.walk.dto.response.WalkDetailResponse;
import com.widyu.goal.walk.dto.response.WalkMonthlyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@Tag(name = "Walk", description = "걷기 관리 API")
public interface WalkDocs {

    @Operation(
            summary = "걷기 홈 - 월별 현황 조회",
            description = """
                    시니어 걷기 홈 - 월별 걷기 통계 및 일별 데이터를 조회합니다.

                    **기능:**
                    - 이전 달 / 현재 달(또는 요청 달) 목표 달성 통계
                    - 요청한 월의 일별 걷기 데이터 (목표/실제 걸음 수)

                    **Summary의 previous 계산:**
                    - 요청받은 year, month에서 -1개월
                    - 예: 2025년 10월 요청 시 → previous는 2025년 9월

                    **Summary의 current 계산:**
                    - 요청 월이 현재 월이면 → 현재 월 통계
                    - 요청 월이 과거/미래이면 → 요청 월 통계

                    **권한:**
                    - memberId가 null → 본인의 걸음 기록 조회
                    - memberId가 있음 → 보호자가 가족으로 연결된 시니어의 걸음 기록 조회
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "권한 없음 (가족 관계 아님)")
    })
    ApiResponseTemplate<WalkMonthlyResponse> getMonthlyStats(
            @Parameter(description = "연도", example = "2025", required = true)
            @RequestParam int year,
            @Parameter(description = "월", example = "10", required = true)
            @RequestParam int month,
            @Parameter(description = "대상 시니어 ID (null이면 본인)", example = "1")
            @RequestParam(required = false) Long memberId
    );

    @Operation(
            summary = "시니어 걷기 홈 - 오늘 걸음 수 조회",
            description = """
                    오늘 날짜의 걷기 목표 및 실제 걸음 수를 조회합니다.

                    **기능:**
                    - 목표 걸음 수 (goal)
                    - 실제 걸음 수 (actual), 초기값 0
                    - 리워드 포인트 (point), 목표 달성 시 25포인트

                    **목표 처리:**
                    - 오늘 날짜에 Walk 기록이 있으면 → 실제 기록 반환
                    - Walk 기록 없지만 기본 목표 설정됨 → 기본 목표 반환 (actual=0, point=0)
                    - 기본 목표도 없으면 → data는 null 반환

                    **권한:**
                    - memberId가 null → 본인의 걸음 기록 조회
                    - memberId가 있음 → 보호자가 가족으로 연결된 시니어의 걸음 기록 조회
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공 (목표 없으면 data null)"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "권한 없음 (가족 관계 아님)")
    })
    ApiResponseTemplate<WalkDetailResponse> getWalkDetail(
            @Parameter(description = "대상 시니어 ID (null이면 본인)", example = "1")
            @RequestParam(required = false) Long memberId
    );

    @Operation(
            summary = "걷기 목표 설정/수정",
            description = """
                    시니어의 기본 걷기 목표를 설정하거나 수정합니다.

                    **기능:**
                    - 시니어 프로필의 기본 걷기 목표(defaultWalkGoal) 업데이트
                    - 처음 설정하든 수정하든 동일한 API 사용
                    - 기본 목표는 매일 자동으로 적용됨
                    - 변경 시 내일부터 새 목표가 적용됨 (오늘은 기존 기록 유지)

                    **제약사항:**
                    - 최대 10000보까지 설정 가능

                    **권한:**
                    - memberId가 null → 본인의 걸음 목표 설정/수정
                    - memberId가 있음 → 보호자가 가족으로 연결된 시니어의 걸음 목표 설정/수정
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "설정/수정 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 (유효하지 않은 걸음 수 등)"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "권한 없음 (가족 관계 아님)")
    })
    ApiResponseTemplate<Void> setOrUpdateGoal(
            @Parameter(description = "대상 시니어 ID (null이면 본인)", example = "1")
            @RequestParam(required = false) Long memberId,
            @Valid @RequestBody SetGoalRequest request
    );

    @Operation(
            summary = "걸음 수 연동",
            description = """
                    지정한 날짜의 실제 걸음 수를 자동으로 연동합니다.

                    **기능:**
                    - 지정한 날짜의 걸음 수 업데이트
                    - 목표 달성 시 포인트 자동 지급 (25포인트)
                    - Walk 기록이 없으면 기본 목표로 자동 생성 후 연동
                    - 목표 달성 여부를 반환 (achieved: true/false)

                    **처리 순서:**
                    1. 지정한 날짜가 오늘을 포함한 최근 7일 이내인지 검증
                    2. 지정한 날짜의 Walk 기록 조회
                    3. 없으면 → 기본 걷기 목표(defaultWalkGoal)로 자동 생성
                    4. 실제 걸음 수 업데이트
                    5. 목표 달성 시 → 자동으로 포인트 지급

                    **제약사항:**
                    - date는 오늘을 포함해 최근 7일 이내여야 함 (예: 9/13 요청 시 9/7~9/13 허용)
                    - 기본 목표도 없으면 에러 발생 (먼저 목표 설정 필요)

                    **권한:**
                    - 시니어 본인만 가능 (보호자는 연동 불가)

                    **반환값:**
                    - achieved: 목표 달성 여부 (boolean)
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "연동 성공 (목표 달성 여부 포함)"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 (목표 미설정, 허용되지 않은 연동 날짜 등)"),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    ApiResponseTemplate<UpdateStepsResponse> syncSteps(
            @Valid @RequestBody UpdateStepsRequest request
    );

    @Operation(
            summary = "내일부터 적용될 목표 걸음수 조회",
            description = """
                    내일부터 적용될 목표 걸음수를 조회합니다.

                    **기능:**
                    - 시니어 프로필의 기본 걷기 목표(defaultWalkGoal) 조회
                    - 목표를 수정한 경우, 수정된 목표(내일부터 적용될 목표)를 조회

                    **처리 로직:**
                    - 처음 목표 설정 → 오늘부터 적용되므로, 이 API는 설정된 목표 반환
                    - 목표 수정 → 오늘은 기존 목표 유지, 내일부터 새 목표 적용
                    - 수정한 목표를 바로 확인할 수 있음

                    **반환값:**
                    - steps: 내일부터 적용될 목표 걸음수
                    - 기본 목표가 설정되지 않았으면 data null 반환

                    **권한:**
                    - memberId가 null → 본인의 목표 조회
                    - memberId가 있음 → 보호자가 가족으로 연결된 시니어의 목표 조회
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공 (목표 미설정 시 data null)"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 (시니어 프로필 없음 등)"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "권한 없음 (가족 관계 아님)")
    })
    ApiResponseTemplate<UpcomingGoalResponse> getUpcomingGoal(
            @Parameter(description = "대상 시니어 ID (null이면 본인)", example = "1")
            @RequestParam(required = false) Long memberId
    );
}
