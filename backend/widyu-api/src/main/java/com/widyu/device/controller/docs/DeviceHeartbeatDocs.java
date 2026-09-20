package com.widyu.device.controller.docs;

import com.widyu.device.dto.request.DeviceHeartbeatRequest;
import com.widyu.device.dto.response.DeviceHeartbeatResponse;
import com.widyu.global.response.ApiResponseTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Device Heartbeat", description = "기기 상태 하트비트 수신 API")
public interface DeviceHeartbeatDocs {

    @Operation(
            summary = "기기 상태 하트비트 저장",
            description = """
                    폰이 60초마다 보내는 기기 상태를 저장합니다(작업지시서 B7).
                    회원은 액세스 토큰에서 확정하며 요청 본문으로 받지 않습니다.

                    **왜 보내나**: 자료가 비었을 때 왜 비었는지(배터리·미착용·앱 종료·네트워크)를
                    아는 유일한 근거입니다. 워치를 벗어 두면 그 사실이 여기에 남아 같은 구간의
                    심박 공백과 나란히 조회됩니다.

                    **멱등**: `seq`가 없는 스트림이라 `(device_id, session_id, ts_ms)`가 멱등 키입니다.
                    같은 조합이 다시 오면 저장 없이 `DUPLICATE`를 반환합니다.

                    **워치가 끊겼을 때**: `watch.connected=false`로 보내고 나머지 `watch.*`는 null로
                    둡니다. **0으로 채우지 마십시오** — 배터리 0%·큐 0으로 읽힙니다.

                    **모르는 필드**는 거절하지 않습니다. 원문 바이트가 그대로 보관됩니다.
                    """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = DeviceHeartbeatRequest.class),
                            examples = {
                                    @ExampleObject(
                                            name = "워치 연결됨",
                                            value = """
                                                    {
                                                      "v": 1,
                                                      "ts_ms": 1760000000123,
                                                      "device_id": "ph-9c1",
                                                      "session_id": "s-20260920-01",
                                                      "study_id": null,
                                                      "participation_id": null,
                                                      "run_id": null,
                                                      "phone": {
                                                        "battery_pct": 71,
                                                        "charging": false,
                                                        "os": "android/14",
                                                        "app_ver": "1.4.2",
                                                        "socket_connected": true,
                                                        "location_permission": "always",
                                                        "background_restricted": false,
                                                        "queue_depth": 0
                                                      },
                                                      "watch": {
                                                        "connected": true,
                                                        "device_id": "gw-3f2a",
                                                        "battery_pct": 63,
                                                        "app_ver": "1.4.2",
                                                        "hr_session": "RUNNING",
                                                        "on_body": true,
                                                        "last_hr_ts_ms": 1760000000000,
                                                        "last_imu_ts_ms": 1760000000100,
                                                        "queue_depth": 0
                                                      }
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "워치 끊김 (나머지는 null)",
                                            value = """
                                                    {
                                                      "v": 1,
                                                      "ts_ms": 1760000060123,
                                                      "device_id": "ph-9c1",
                                                      "session_id": "s-20260920-01",
                                                      "phone": {
                                                        "battery_pct": 68,
                                                        "charging": false,
                                                        "os": "android/14",
                                                        "app_ver": "1.4.2",
                                                        "socket_connected": true,
                                                        "location_permission": "always",
                                                        "background_restricted": false,
                                                        "queue_depth": 3
                                                      },
                                                      "watch": {
                                                        "connected": false,
                                                        "device_id": null,
                                                        "battery_pct": null,
                                                        "app_ver": null,
                                                        "hr_session": null,
                                                        "on_body": null,
                                                        "last_hr_ts_ms": null,
                                                        "last_imu_ts_ms": null,
                                                        "queue_depth": null
                                                      }
                                                    }
                                                    """
                                    )
                            }
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
                                              "message": "기기 하트비트를 저장했습니다.",
                                              "data": {
                                                "tsMs": 1760000000123,
                                                "result": "STORED"
                                              }
                                            }
                                            """
                            ),
                            @ExampleObject(
                                    name = "중복 (같은 device_id·session_id·ts_ms 재전송)",
                                    value = """
                                            {
                                              "code": "200",
                                              "message": "기기 하트비트를 저장했습니다.",
                                              "data": {
                                                "tsMs": 1760000000123,
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
            description = "본문을 읽을 수 없거나 필수 필드 누락·값 범위 위반",
            content = @Content(
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "DEVICE_4000",
                                      "message": "하트비트 형식이 올바르지 않습니다.",
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
    ApiResponseTemplate<DeviceHeartbeatResponse> ingestHeartbeat(byte[] payload);
}
