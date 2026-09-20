package com.widyu.location.access.controller.docs;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.location.access.dto.response.LocationAccessLogResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;

@Tag(name = "LocationAccessLog", description = "위치 열람 기록 API")
public interface LocationAccessLogDocs {

    @Operation(
            summary = "내 위치를 누가 언제 봤는지 조회",
            description = """
                    보호자가 내 위치를 읽은 기록을 최근순으로 돌려줍니다(위치정보법 제16조②).

                    **좌표는 없습니다.** 남기는 것은 「누가·언제·어떤 경로로 봤는가」뿐입니다.

                    **`path`**는 다섯 가지입니다. `REST_LAST`(최근 위치 조회),
                    `REST_TRAIL`(15분 이동 경로), `REST_FAMILY`(가족 시니어 목록 — 목록에 담긴
                    시니어마다 한 줄), `WS_SUBSCRIBE`(실시간 구독 — 구독 시점에 한 줄이고
                    그 뒤 흘러가는 위치 메시지마다 늘지 않습니다), `HOME_OUTING`(보호자 홈 화면의
                    외출 여부 카드).

                    **기간**을 주지 않으면 최근 30일입니다. `from`·`to`는 ISO date-time
                    (`2026-09-01T00:00:00`)입니다.

                    **`notifiedAt`이 비어 있는 줄**은 아직 통보가 나가지 않은 줄입니다.
                    같은 보호자가 짧은 시간 안에 반복해 본 건은 통보를 하나로 합치고,
                    합쳐진 줄은 다음 요약 통보에서 함께 세어 알립니다.

                    **다른 사람의 기록은 볼 수 없습니다.** 대상은 언제나 요청한 본인입니다.
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
                                      "message": "위치 열람 기록 조회 성공",
                                      "data": {
                                        "content": [
                                          {
                                            "id": 912,
                                            "viewerMemberId": 55,
                                            "viewerName": "김보호",
                                            "path": "REST_LAST",
                                            "accessedAt": "2026-09-21T10:12:03",
                                            "notifiedAt": "2026-09-21T10:12:03"
                                          },
                                          {
                                            "id": 911,
                                            "viewerMemberId": 55,
                                            "viewerName": "김보호",
                                            "path": "WS_SUBSCRIBE",
                                            "accessedAt": "2026-09-21T10:11:58",
                                            "notifiedAt": null
                                          }
                                        ],
                                        "totalElements": 2,
                                        "totalPages": 1,
                                        "number": 0,
                                        "size": 30
                                      }
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<Page<LocationAccessLogResponse>> getMyAccessLogs(
            LocalDateTime from, LocalDateTime to, int page, int size);
}
