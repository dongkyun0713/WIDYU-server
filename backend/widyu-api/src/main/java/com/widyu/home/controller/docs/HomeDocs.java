package com.widyu.home.controller.docs;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.home.dto.response.GuardianHomeCardsResponse;
import com.widyu.home.dto.response.GuardianSeniorListResponse;
import com.widyu.home.dto.response.SeniorHomeCardsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Home", description = "홈 화면 API")
public interface HomeDocs {

    @Operation(
            summary = "시니어 - 홈 카드 통합 조회",
            description = """
                    시니어 홈 화면의 카드 5종(심박수·약복용·앨범·건강달력·걷기)을 한 번에 조회합니다.

                    **앨범 점수 산정 방식:**
                    - 기본 점수 = (좋아요 수 × 3) + (댓글 수 × 2)
                    - 날짜 보너스 = 오늘과 같은 월/일의 사진이면 +10점 (이 날의 추억)
                    - 상위 점수 3장 반환, 앨범 없으면 빈 배열
                    - 각 카드의 isUnlocked는 앨범 잠금 해제 여부입니다. 보호자가 올리고 아직 해금하지 않은 앨범이면 false이고, 썸네일이 없으면 원본 URL 대신 thumbnailUrl이 null입니다.

                    **건강달력 카드:**
                    - healthSchedule: 현재 시각 이후 1개월 이내 가장 가까운 일정
                    - todaySchedules: 오늘 00:00~24:00 일정 전체, 일시 오름차순. 예정 시각이 지난 일정과 완료된 일정도 포함하며 항목마다 progressStatus와 elapsed(예정 시각 경과 여부)를 줍니다.

                    **null 반환 케이스:**
                    - medicine: 등록된 약 스케줄 없음
                    - healthSchedule: 현재 시각 이후 1개월 이내 일정 없음. 클라이언트는 이 자리에 일정 추가 요청 컴포넌트를 표시합니다.
                    - walk: 오늘 걷기 데이터 및 기본 목표 없음
                    - albums: 가족 앨범 없음 (빈 배열 반환)
                    - todaySchedules: 오늘 일정 없음 (빈 배열 반환)

                    **권한:**
                    - 시니어 본인만 가능
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패")
    })
    ApiResponseTemplate<SeniorHomeCardsResponse> getSeniorHomeCards();

    @Operation(
            summary = "보호자 - 홈 카드 통합 조회",
            description = """
                    보호자 홈 화면의 카드(심박수·약복용·앨범·건강달력·걷기)를 한 번에 조회합니다.

                    **memberId 미입력 시:** 첫 번째 연결된 시니어 자동 선택

                    **건강달력 카드:**
                    - healthSchedule: 현재 시각 이후 가장 가까운 일정 (기간 제한 없음). 없으면 null이며, 클라이언트는 이 자리에 일정 추가 요청 컴포넌트를 표시합니다.
                    - todaySchedules: 오늘 00:00~24:00 일정 전체, 일시 오름차순. 예정 시각이 지난 일정과 완료된 일정도 포함하며 항목마다 progressStatus와 elapsed(예정 시각 경과 여부)를 줍니다. 없으면 빈 배열입니다.

                    **권한:**
                    - 보호자가 연결된 시니어의 정보만 조회 가능
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "연결된 부모님 없음"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "접근 권한 없음")
    })
    ApiResponseTemplate<GuardianHomeCardsResponse> getGuardianHomeCards(
            @Parameter(description = "시니어 ID - null이면 첫 번째 연결 시니어 자동 선택")
            Long memberId
    );

    @Operation(
            summary = "보호자 - 가족 시니어 목록 조회",
            description = """
                    보호자 홈에서 선택 가능한 가족 시니어 목록을 조회합니다.

                    **응답 필드:**
                    - memberId: 시니어 회원 ID
                    - name: 시니어 이름
                    - profileImage: 시니어 프로필 이미지 URL

                    연결된 가족이 없으면 빈 배열을 반환합니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패"),
            @ApiResponse(responseCode = "403", description = "보호자만 접근 가능")
    })
    ApiResponseTemplate<GuardianSeniorListResponse> getGuardianSeniors();
}
