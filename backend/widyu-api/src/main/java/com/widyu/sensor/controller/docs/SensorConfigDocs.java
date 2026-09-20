package com.widyu.sensor.controller.docs;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.sensor.dto.response.SensorConfigResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Sensor Config", description = "워치 수집 설정 하달 API")
public interface SensorConfigDocs {

    @Operation(
            summary = "수집 설정 조회",
            description = """
                    워치가 적용할 수집 설정을 내려줍니다. 회원은 액세스 토큰에서 확정합니다.

                    **수집 모드는 서버가 정합니다.** 호출한 회원에게 열린 측정회차가 있으면 `research`,
                    없으면 `product`입니다. 회차를 열고 닫는 것(#643)이 곧 전환 수단이라 별도 변경 API는 없습니다.

                    **나머지 값은 서버 설정값**이며 모든 회원에게 같습니다. 참가자별 개별 설정은 없습니다.

                    **`refreshSec`은 다시 읽을 권고 주기**입니다(기본 60초). 실제 재조회 주기는 앱 정책입니다.

                    앱이 실제 적용한 값은 배치에 실려 돌아오며, 서버가 내려보낸 값과 다르면
                    `sensor_batch.config_mismatch`로 기록합니다. **배치를 거부하지는 않습니다** —
                    설정이 적용되지 않은 채 도는 상황을 조용히 넘기지 않는 것이 목적입니다.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "조회 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = {
                            @ExampleObject(
                                    name = "연구 세션 중 (열린 회차 있음)",
                                    value = """
                                            {
                                              "code": "200",
                                              "message": "수집 설정을 조회했습니다.",
                                              "data": {
                                                "collectionMode": "research",
                                                "gyroMode": "continuous",
                                                "accFsHz": 50,
                                                "batchSec": 1,
                                                "impactThresholdG": 1.8,
                                                "backfillBeforeSec": 2,
                                                "backfillAfterSec": 10,
                                                "hrUploadSec": 1,
                                                "locationMoveSec": 5,
                                                "locationKeepaliveSec": 60,
                                                "heartbeatSec": 60,
                                                "refreshSec": 60
                                              }
                                            }
                                            """
                            ),
                            @ExampleObject(
                                    name = "평시 (열린 회차 없음)",
                                    value = """
                                            {
                                              "code": "200",
                                              "message": "수집 설정을 조회했습니다.",
                                              "data": {
                                                "collectionMode": "product",
                                                "gyroMode": "continuous",
                                                "accFsHz": 50,
                                                "batchSec": 1,
                                                "impactThresholdG": 1.8,
                                                "backfillBeforeSec": 2,
                                                "backfillAfterSec": 10,
                                                "hrUploadSec": 1,
                                                "locationMoveSec": 5,
                                                "locationKeepaliveSec": 60,
                                                "heartbeatSec": 60,
                                                "refreshSec": 60
                                              }
                                            }
                                            """
                            )
                    }
            )
    )
    @ApiResponse(
            responseCode = "401",
            description = "인증 필요",
            content = @Content(examples = @ExampleObject(value = """
                    {"code": "AUTH_4010", "message": "인증이 필요합니다.", "data": null}
                    """))
    )
    ApiResponseTemplate<SensorConfigResponse> getConfig();
}
