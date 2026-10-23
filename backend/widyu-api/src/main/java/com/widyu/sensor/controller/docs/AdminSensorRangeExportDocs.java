package com.widyu.sensor.controller.docs;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.sensor.dto.request.SensorRangeExportRequest;
import com.widyu.sensor.dto.response.SensorRangeExportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Admin Sensor Range Export", description = "회원별 시간 범위 IMU·심박 ZIP 내보내기")
public interface AdminSensorRangeExportDocs {

    @Operation(summary = "센서 시간 범위 ZIP 요청",
            description = "관리자만 호출합니다. UTC epoch ms 시작 포함·끝 제외, 최대 24시간입니다. "
                    + "한 회원의 IMU(가속도·자이로)·심박 샘플을 비동기로 ZIP으로 만듭니다. "
                    + "같은 회원·구간의 진행 중 작업은 재사용합니다.")
    @ApiResponse(responseCode = "202", description = "작업 접수")
    @ApiResponse(responseCode = "400", description = "잘못된 시간 범위")
    @ApiResponse(responseCode = "404", description = "회원 없음")
    ApiResponseTemplate<SensorRangeExportResponse> request(Long memberId, SensorRangeExportRequest request);

    @Operation(summary = "센서 시간 범위 ZIP 상태 조회",
            description = "QUEUED/RUNNING/DONE/FAILED를 조회합니다. DONE이면 15분 유효한 다운로드 URL을 새로 발급합니다.")
    @ApiResponse(responseCode = "200", description = "상태 조회")
    @ApiResponse(responseCode = "404", description = "작업 없음 또는 회원 불일치")
    ApiResponseTemplate<SensorRangeExportResponse> status(Long memberId, String exportId);
}
