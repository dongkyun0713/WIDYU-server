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
            summary = "원시 센서 배치 저장",
            description = """
                    워치·폰의 원시 센서 배치 1건을 재표본화 없이 저장합니다.
                    회원은 액세스 토큰에서 확정하며 요청 본문으로 받지 않습니다.

                    **저장 구조**: 배치 1건 = S3 객체 1개 + `sensor_batch` 인덱스 행 1개 (ADR-0030)

                    **중복 판별**: `(회원, deviceId, sessionId, streamType, seq)`가 같으면 같은 배치입니다.
                    이미 저장돼 있으면 S3 업로드 없이 `DUPLICATE`를 반환하므로 재전송은 멱등합니다.

                    **스트림별 샘플 형식** (서버는 정수 `t`만 검증합니다):
                    - `WATCH_ACCEL`, `PHONE_ACCEL`: `{t, x, y, z}` 정수 mg, 중력 포함 기기 좌표계
                    - `WATCH_GYRO`, `PHONE_GYRO`: `{t, x, y, z}` 정수 mrad/s
                    - `PHONE_LOCATION`: `{t, lat, lon, accuracy}` 도·도·미터

                    **제약**: 샘플 1~1000개, 재직렬화한 JSON 32,768바이트 이하,
                    모르는 최상위 필드가 있으면 400으로 거절합니다.

                    이 REST 경로는 오프라인 재전송·자이로 보강용입니다. 평시 1초 배치는 WebSocket으로 보냅니다.
                    """
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
                                              "data": { "seq": 1234, "result": "STORED" }
                                            }
                                            """
                            ),
                            @ExampleObject(
                                    name = "중복 (재전송)",
                                    value = """
                                            {
                                              "code": "200",
                                              "message": "센서 배치를 저장했습니다.",
                                              "data": { "seq": 1234, "result": "DUPLICATE" }
                                            }
                                            """
                            )
                    }
            )
    )
    @ApiResponse(
            responseCode = "400",
            description = "배치 크기 초과·샘플 시각 누락·필수 필드 누락",
            content = @Content(
                    examples = {
                            @ExampleObject(
                                    name = "크기 초과",
                                    value = """
                                            {
                                              "code": "SENSOR_4000",
                                              "message": "센서 배치 크기가 허용 범위를 초과했습니다.",
                                              "data": null
                                            }
                                            """
                            ),
                            @ExampleObject(
                                    name = "샘플 시각 누락",
                                    value = """
                                            {
                                              "code": "SENSOR_4001",
                                              "message": "센서 샘플에 정수 측정 시각(t)이 없습니다.",
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
    ApiResponseTemplate<SensorBatchResultResponse> ingestBatch(SensorBatchRequest request);
}
