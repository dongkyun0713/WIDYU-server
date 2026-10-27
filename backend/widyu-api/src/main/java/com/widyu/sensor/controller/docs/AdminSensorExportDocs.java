package com.widyu.sensor.controller.docs;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;
import org.springframework.http.ResponseEntity;

@Tag(name = "Admin Sensor Export", description = "회원·시간대 센서 자료 CSV 내보내기 API (운영자)")
public interface AdminSensorExportDocs {

    @Operation(
            summary = "시간대 센서 자료 CSV 내보내기",
            description = """
                    한 회원의 지정 시간대에 앞뒤 여유를 붙여, 위치 원본과 심박·움직임 자료를 CSV zip 하나로 내려받습니다.
                    회차(`run_id`)가 없어도 됩니다. 시각은 **한국 시간**으로 받습니다.

                    - 내보내는 구간: `[from - padMinutes, to + padMinutes]`(양 끝 포함), 최대 90분
                    - 모든 행의 `phase`: `from` 앞 `before`, `to` 뒤 `after`, 그 사이 `during`
                    - 시각 열: `ts_kst`(한국 시간)와 `ts_ms`(UTC epoch ms)

                    zip 구성:
                    - `location.csv`: 위치 원본(`location_fix`) 한 점마다 한 줄. 정확도·속도·방향·고도 포함
                    - `hr.csv`: 심박 묶음 S3 원문의 표본마다 한 줄
                    - `imu.csv`: 가속도(`mg`)·자이로(`mrad_s`) 표본마다 한 줄. 기기 시계를 epoch ms로 환산
                    - `sensor_batches.csv`: 구간과 겹치는 센서 묶음 목록(착용·배터리·품질)
                    - `device_heartbeat.csv`: 기기 상태(워치 연결·착용·배터리, 폰 배터리)
                    - `summary.csv`: 요청 조건, 파일별 행 수, 원문이 손상돼 건너뛴 묶음(`skipped_batch`)

                    관리자 접속기록에 관리자·대상 회원이 남습니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "CSV zip (`application/zip`)")
    @ApiResponse(
            responseCode = "400",
            description = "시간대 오류(`from >= to`, 90분 초과) 또는 padMinutes 범위 오류",
            content = @Content(examples = @ExampleObject(value = """
                    {"code": "SENSOR_4004", "message": "내보내기 시간대가 올바르지 않습니다.", "data": null}
                    """))
    )
    @ApiResponse(
            responseCode = "404",
            description = "회원 없음",
            content = @Content(examples = @ExampleObject(value = """
                    {"code": "MEMBER_4041", "message": "회원을 찾을 수 없습니다.", "data": null}
                    """))
    )
    ResponseEntity<byte[]> export(
            @Parameter(description = "대상 회원 ID", example = "28") Long memberId,
            @Parameter(description = "시작 시각(탄 시각), 한국 시간", example = "2026-10-30T09:10:00")
            LocalDateTime from,
            @Parameter(description = "끝 시각(내린 시각), 한국 시간", example = "2026-10-30T09:28:00")
            LocalDateTime to,
            @Parameter(description = "앞뒤 여유(분), 기본 10")
            @Min(value = 0, message = "padMinutes는 0 이상 60 이하여야 합니다.")
            @Max(value = 60, message = "padMinutes는 0 이상 60 이하여야 합니다.") int padMinutes);
}
