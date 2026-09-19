package com.widyu.sensor.controller.docs;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.sensor.dto.request.SensorBatchRequest;
import com.widyu.sensor.dto.response.SensorBatchResultResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Sensor Batch", description = "원시 센서 배치 수신 API")
public interface SensorBatchDocs {

    @Operation(
            summary = "IMU 배치 저장 (v2 형식)",
            description = """
                    워치·폰의 IMU 배치 1건을 재표본화 없이 저장합니다.
                    회원은 액세스 토큰에서 확정하며 요청 본문으로 받지 않습니다.

                    **저장 구조**: 배치 1건 = S3 객체 1개 + `sensor_batch` 인덱스 행 1개 (ADR-0030).
                    S3에는 **받은 원문 바이트를 그대로** 올립니다. 재직렬화·정렬·압축을 하지 않으므로
                    꺼냈을 때 보낸 본문과 바이트 단위로 같습니다.

                    **멱등**: 앱이 붙인 불변 `batch_id`가 멱등 키입니다. 같은 `batch_id`가 다시 오면
                    S3 업로드 없이 `DUPLICATE`를 반환합니다. 내용이 달라지는 재전송은 새 `batch_id`에
                    `resend.original_batch_id`로 계보를 남깁니다.

                    **나노초 시각**: `anchor_elapsed_ns`·`t0_elapsed_ns`·`event_elapsed_ns`는
                    64비트 정밀도를 지키기 위해 **10진 문자열**로 보냅니다. JSON number로 보내면 400입니다.

                    **축**: 가속도(`acc.mg`, 정수 mg)와 자이로(`gyro.mrads`, 정수 mrad/s)는 한 배치에 오되
                    시간축이 독립이라 샘플 수가 달라도 됩니다. `dt_ns`는 길이 `n-1`, 값 배열은 `n`행 3열입니다.
                    자이로가 없으면 `gyro: null`로 보냅니다. **0 배열로 채우지 마십시오** — 정지 상태로 읽힙니다.

                    **모르는 필드**는 거절하지 않습니다. 원문이 그대로 보관되므로 손실이 없습니다.
                    """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SensorBatchRequest.class),
                            examples = @ExampleObject(
                                    name = "워치 가속도 배치",
                                    value = """
                                            {
                                              "v": 2,
                                              "stream": "imu_watch",
                                              "source": "watch",
                                              "batch_id": "01j8zk3v9x2q4m7n8p1r5s6t7u",
                                              "device_id": "gw-3f2a",
                                              "session_id": "s-20260919-01",
                                              "seq": 88213,
                                              "study_id": null,
                                              "participation_id": null,
                                              "run_id": null,
                                              "clock": {
                                                "boot_id": "b7c1",
                                                "clock_mapping_id": "cm-01",
                                                "anchor_elapsed_ns": "993847100000001",
                                                "anchor_epoch_ms": 1760000000000,
                                                "uncertainty_ms": 2.0
                                              },
                                              "acc": {
                                                "fs_hz_requested": 50,
                                                "n": 3,
                                                "t0_elapsed_ns": "993847112340001",
                                                "dt_ns": [19998417, 20003005],
                                                "mg": [[20, -980, 110], [22, -979, 108], [21, -981, 109]]
                                              },
                                              "gyro": null,
                                              "trigger": null,
                                              "gyro_backfill": false,
                                              "backfill_for": null,
                                              "resend": null,
                                              "collection_mode": "product",
                                              "gyro_mode": "continuous",
                                              "on_body": true,
                                              "wear_state": null,
                                              "missing_reason": null,
                                              "watch_battery_pct": 63
                                            }
                                            """
                            )
                    )
            )
    )
    @ApiResponse(
            responseCode = "200",
            description = "수신 완료 (저장 또는 중복)",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = {
                            @ExampleObject(
                                    name = "저장",
                                    value = """
                                            {
                                              "code": "200",
                                              "message": "센서 배치를 저장했습니다.",
                                              "data": {
                                                "batchId": "01j8zk3v9x2q4m7n8p1r5s6t7u",
                                                "seq": 88213,
                                                "result": "STORED"
                                              }
                                            }
                                            """
                            ),
                            @ExampleObject(
                                    name = "중복 (같은 batch_id 재전송)",
                                    value = """
                                            {
                                              "code": "200",
                                              "message": "센서 배치를 저장했습니다.",
                                              "data": {
                                                "batchId": "01j8zk3v9x2q4m7n8p1r5s6t7u",
                                                "seq": 88213,
                                                "result": "DUPLICATE"
                                              }
                                            }
                                            """
                            )
                    }
            )
    )
    @ApiResponse(
            responseCode = "400",
            description = "본문·축·필드 검증 실패",
            content = @Content(
                    examples = {
                            @ExampleObject(
                                    name = "본문을 읽을 수 없음 (나노초가 문자열이 아님)",
                                    value = """
                                            {
                                              "code": "SENSOR_4002",
                                              "message": "센서 배치 본문을 읽을 수 없습니다.",
                                              "data": null
                                            }
                                            """
                            ),
                            @ExampleObject(
                                    name = "축 구조 오류 (dt_ns 길이·mg 열 수·dt_ns 범위)",
                                    value = """
                                            {
                                              "code": "SENSOR_4001",
                                              "message": "센서 축 구조가 올바르지 않습니다.",
                                              "data": null
                                            }
                                            """
                            ),
                            @ExampleObject(
                                    name = "필드 오류 (필수 누락·값 집합·보강/재전송 조건)",
                                    value = """
                                            {
                                              "code": "SENSOR_4003",
                                              "message": "센서 배치 필드가 올바르지 않습니다.",
                                              "data": null
                                            }
                                            """
                            ),
                            @ExampleObject(
                                    name = "본문 크기 초과",
                                    value = """
                                            {
                                              "code": "SENSOR_4000",
                                              "message": "센서 배치 크기가 허용 범위를 초과했습니다.",
                                              "data": null
                                            }
                                            """
                            )
                    }
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
    @ApiResponse(
            responseCode = "500",
            description = "S3 업로드 실패 (인덱스 행을 저장하지 않으므로 재전송하면 됩니다)",
            content = @Content(
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "FILE_5000",
                                      "message": "파일 업로드에 실패했습니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<SensorBatchResultResponse> ingestBatch(byte[] payload);
}
