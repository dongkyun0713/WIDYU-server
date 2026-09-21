package com.widyu.consent.controller.docs;

import com.widyu.consent.dto.request.ConsentSubmitRequest;
import com.widyu.consent.dto.request.ConsentWithdrawRequest;
import com.widyu.consent.dto.response.ConsentStateResponse;
import com.widyu.global.response.ApiResponseTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Consent", description = "인앱 동의 기록 API")
public interface ConsentDocs {

    @Operation(
            summary = "항목별 동의 제출",
            description = """
                    앱에서 받은 항목별 동의를 동의문 판·서버 시각과 함께 기록합니다(정책서 B 1.5.10).

                    **항목 이름**은 서버가 고정합니다. `PRIVACY_PERSONAL`(개인정보 수집·이용),
                    `PRIVACY_HEALTH`(민감·건강정보), `LOCATION`(위치정보 수집·이용),
                    `GUARDIAN_LOCATION_PROVIDE`(보호자에게 위치 제공),
                    `LOCATION_NOTICE_BATCHED`(위치 제공사실을 모아서 통보받는 데 동의),
                    `RETENTION_NOTICE`(보유 기간 고지 확인) 여섯 가지이며 다른 값은 400입니다.

                    **한 번 남긴 기록은 바뀌지 않습니다.** 같은 항목을 다시 보내면 덮어쓰지 않고
                    행이 하나 더 쌓이며, 현재 상태는 항목별 최신 행입니다. 「언제 다시 동의했는지」도
                    남겨야 할 사실이기 때문입니다.

                    **동의하지 않은 항목도 보낼 수 있습니다**(`false`). 보내지 않은 항목은
                    기록이 없는 상태로 남고 응답 목록에도 나오지 않습니다.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "기록 완료. 기록 뒤의 현재 상태를 돌려줍니다",
            content = @Content(
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "200",
                                      "message": "동의를 기록했습니다.",
                                      "data": {
                                        "memberId": 1023,
                                        "items": [
                                          {
                                            "key": "PRIVACY_PERSONAL",
                                            "granted": true,
                                            "version": "app-consent-v1",
                                            "recordedAt": "2026-09-21T10:00:00"
                                          },
                                          {
                                            "key": "LOCATION_NOTICE_BATCHED",
                                            "granted": false,
                                            "version": "app-consent-v1",
                                            "recordedAt": "2026-09-21T10:00:00"
                                          }
                                        ]
                                      }
                                    }
                                    """
                    )
            )
    )
    @ApiResponse(
            responseCode = "400",
            description = "모르는 항목 이름이거나 동의 항목이 비어 있음",
            content = @Content(
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "CONSENT_4000",
                                      "message": "알 수 없는 동의 항목입니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<ConsentStateResponse> submitConsents(ConsentSubmitRequest request);

    @Operation(
            summary = "현재 동의 상태 조회",
            description = """
                    본인의 항목별 최신 의사를 돌려줍니다. 기록이 한 번도 없는 항목은 목록에 없습니다 —
                    「동의하지 않았다」와 「아직 묻지 않았다」는 다르기 때문입니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "조회 성공")
    ApiResponseTemplate<ConsentStateResponse> getCurrentConsents();

    @Operation(
            summary = "항목별 동의 철회",
            description = """
                    보낸 항목마다 「철회」 행을 남깁니다. 기존 행을 지우거나 고치지 않습니다.
                    동의문 판은 직전 기록의 값을 그대로 복사하며, 직전 기록이 없으면 `-`로 남깁니다.

                    직전 기록이 없거나 이미 철회 상태여도 400이 아니라 철회 행을 남깁니다.
                    철회 의사를 밝힌 시각 자체가 기록할 사실이기 때문입니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "철회 기록 완료. 기록 뒤의 현재 상태를 돌려줍니다")
    @ApiResponse(
            responseCode = "400",
            description = "모르는 항목 이름이거나 항목 목록이 비어 있음",
            content = @Content(
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "CONSENT_4001",
                                      "message": "동의 항목이 비어 있습니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<ConsentStateResponse> withdrawConsents(ConsentWithdrawRequest request);
}
