package com.widyu.run.controller.docs;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.run.dto.request.CollectionRunCloseRequest;
import com.widyu.run.dto.request.CollectionRunOpenRequest;
import com.widyu.run.dto.request.DeviceAssignRequest;
import com.widyu.run.dto.request.DeviceUnassignRequest;
import com.widyu.run.dto.request.RunMarkerRequest;
import com.widyu.run.dto.response.CollectionRunResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Admin Collection Run", description = "측정회차·기기 배정·마커 API (운영자)")
public interface AdminCollectionRunDocs {

    @Operation(
            summary = "측정회차 열기",
            description = """
                    「누가·어떤 기기를·어디에 차고·언제부터 언제까지」를 묶는 측정회차를 엽니다.
                    실증은 기기를 여러 참가자가 돌려 쓰므로, 기기 번호만으로는 자료가 누구 것인지 알 수 없습니다.

                    **제약**
                    - 회원당 열린 회차는 하나입니다. 이미 있으면 409입니다.
                    - 기기가 다른 열린 회차에 배정돼 있으면 409입니다.
                    - `retention`은 통째로 생략할 수 있습니다. `dataPolicy`가 `RETAIN`이면 세 날짜가 모두 필요하고
                      `identifiedUntil ≤ pseudonymizedAt ≤ researchUntil`이어야 합니다.
                    - `collectionMode` 기본값은 `research`, `startedAtMs` 기본값은 서버 현재 시각입니다.

                    `runId`는 서버가 발급합니다. 사람이 읽는 회차 번호는 `protocolRef`에 두십시오.
                    """
    )
    @ApiResponse(responseCode = "200", description = "회차를 열었습니다.")
    @ApiResponse(
            responseCode = "409",
            description = "이미 열린 회차가 있거나 기기가 다른 회차에 배정됨",
            content = @Content(examples = {
                    @ExampleObject(name = "열린 회차 존재", value = """
                            {"code": "RUN_4090", "message": "이미 열린 측정회차가 있습니다.", "data": null}
                            """),
                    @ExampleObject(name = "기기 중복 배정", value = """
                            {"code": "RUN_4091", "message": "이미 다른 열린 회차에 배정된 기기입니다.", "data": null}
                            """)
            })
    )
    @ApiResponse(
            responseCode = "400",
            description = "보존 정보 오류",
            content = @Content(examples = @ExampleObject(value = """
                    {"code": "RUN_4001", "message": "보존 정보가 올바르지 않습니다.", "data": null}
                    """))
    )
    ApiResponseTemplate<CollectionRunResponse> openRun(CollectionRunOpenRequest request);

    @Operation(summary = "측정회차 조회", description = "기기 배정과 마커를 함께 반환합니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(
            responseCode = "404",
            description = "회차 없음",
            content = @Content(examples = @ExampleObject(value = """
                    {"code": "RUN_4041", "message": "측정회차를 찾을 수 없습니다.", "data": null}
                    """))
    )
    ApiResponseTemplate<CollectionRunResponse> getRun(
            @Parameter(description = "회차 ID", example = "run-0f3a…") String runId);

    @Operation(
            summary = "측정회차 닫기",
            description = """
                    열린 회차만 닫을 수 있습니다. 미해제 기기 배정은 종료 시각으로 함께 해제됩니다.
                    남겨 두면 그 기기를 다음 회차에 배정할 수 없습니다.
                    `endedAtMs` 기본값은 서버 현재 시각이며 시작 시각보다 앞설 수 없습니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "회차를 닫았습니다.")
    @ApiResponse(
            responseCode = "400",
            description = "이미 닫힌 회차",
            content = @Content(examples = @ExampleObject(value = """
                    {"code": "RUN_4000", "message": "열린 측정회차가 아닙니다.", "data": null}
                    """))
    )
    ApiResponseTemplate<CollectionRunResponse> closeRun(String runId, CollectionRunCloseRequest request);

    @Operation(
            summary = "기기 배정 추가",
            description = "열린 회차에만 배정할 수 있습니다. 기기가 다른 열린 회차에 배정돼 있으면 409입니다."
    )
    @ApiResponse(responseCode = "200", description = "배정 성공")
    ApiResponseTemplate<CollectionRunResponse> addDevice(String runId, DeviceAssignRequest request);

    @Operation(
            summary = "기기 배정 해제",
            description = "해제 시각은 배정 시각보다 앞설 수 없습니다. 기본값은 서버 현재 시각입니다."
    )
    @ApiResponse(responseCode = "200", description = "해제 성공")
    ApiResponseTemplate<CollectionRunResponse> unassignDevice(
            String runId, String assignmentId, DeviceUnassignRequest request);

    @Operation(
            summary = "마커 등록 (멱등)",
            description = """
                    연구자가 「지금부터 앉았다 일어서기」처럼 찍는 표시입니다. 나중에 그 구간이 무엇이었는지
                    아는 유일한 근거이며 **정답 라벨**입니다. 판정 결과 기록과 같은 자리에 두지 않습니다.

                    **멱등**: 같은 `markerId`가 다시 오면 저장하지 않고 200으로 기존 회차를 돌려줍니다.
                    내용이 다르면 409입니다.

                    **시계**: 누른 기기의 시계와 워치의 시계가 다르므로 `clock`을 함께 받아 센서 배치와
                    같은 규칙으로 `clock_mapping`에 등록합니다. 그래야 표시가 센서 자료와 같은 시간축에 놓입니다.

                    **시각 범위**: `tsMs`는 회차 구간 `[startedAtMs, endedAtMs)` 안이어야 합니다.
                    열린 회차는 상한이 없습니다. 닫힌 회차에도 등록할 수 있습니다(현장에서 늦게 올리는 경우).

                    마커 종류(`kind`) 값 목록은 연구 프로토콜이 정하므로 서버는 검증하지 않습니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "등록 또는 멱등 응답")
    @ApiResponse(
            responseCode = "409",
            description = "같은 markerId에 다른 내용이거나 시계 매핑이 충돌",
            content = @Content(examples = {
                    @ExampleObject(name = "마커 충돌", value = """
                            {"code": "RUN_4092", "message": "같은 마커 ID로 다른 내용이 등록되어 있습니다.", "data": null}
                            """),
                    @ExampleObject(name = "시계 매핑 충돌", value = """
                            {"code": "SENSOR_4090", "message": "시계 환산 기준점 묶음이 등록된 값과 다릅니다.", "data": null}
                            """)
            })
    )
    @ApiResponse(
            responseCode = "400",
            description = "마커 시각이 회차 구간 밖",
            content = @Content(examples = @ExampleObject(value = """
                    {"code": "RUN_4002", "message": "마커 시각이 회차 구간을 벗어났습니다.", "data": null}
                    """))
    )
    ApiResponseTemplate<CollectionRunResponse> registerMarker(String runId, RunMarkerRequest request);
}
