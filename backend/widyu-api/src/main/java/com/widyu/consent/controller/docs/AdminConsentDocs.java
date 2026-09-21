package com.widyu.consent.controller.docs;

import com.widyu.consent.dto.response.ConsentRecordResponse;
import com.widyu.global.response.ApiResponseTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;

@Tag(name = "Admin Consent", description = "관리자 회원 동의 이력 조회 API")
public interface AdminConsentDocs {

    @Operation(
            summary = "회원 동의 이력 조회",
            description = """
                    한 회원이 「어느 항목에 언제 동의했고 언제 철회했는지」 전체를 최근순으로 돌려줍니다.
                    동의와 철회가 같은 목록에 시각순으로 섞여 있습니다(`granted`로 구분합니다).

                    응답에는 이름·전화번호 같은 회원 식별 정보를 싣지 않습니다. 이 화면이 답해야 하는
                    질문에 필요하지 않기 때문입니다.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "조회 성공",
            content = @Content(
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "200",
                                      "message": "회원 동의 이력 조회 성공",
                                      "data": [
                                        {
                                          "key": "LOCATION",
                                          "granted": false,
                                          "version": "app-consent-v1",
                                          "recordedAt": "2026-09-21T11:30:00",
                                          "source": "APP"
                                        },
                                        {
                                          "key": "LOCATION",
                                          "granted": true,
                                          "version": "app-consent-v1",
                                          "recordedAt": "2026-09-21T10:00:00",
                                          "source": "APP"
                                        }
                                      ]
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
    ApiResponseTemplate<List<ConsentRecordResponse>> getConsentHistory(Long memberId);
}
