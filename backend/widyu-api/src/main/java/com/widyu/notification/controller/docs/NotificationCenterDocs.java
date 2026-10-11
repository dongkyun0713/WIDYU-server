package com.widyu.notification.controller.docs;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.notification.dto.response.NotificationCenterResponse;
import com.widyu.notification.dto.response.NotificationReadResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "알림센터", description = "역할별 알림센터 목록과 단건 읽음 처리")
public interface NotificationCenterDocs {
    @Operation(summary = "알림센터 목록 조회",
            description = "filter 기본값은 ALL입니다. 시니어는 ALL/UNREAD/ALBUM/GOAL/MESSAGE, 보호자는 LOCATION도 사용할 수 있습니다. cursor는 이전 응답의 nextCursor를 그대로 전달합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "목록 조회 성공",
                    content = @Content(schema = @Schema(implementation = ApiResponseTemplate.class),
                            examples = @ExampleObject(value = """
                                    {"code":"200","message":"OK","data":{"items":[],"nextCursor":null,
                                    "unreadCounts":{"UNREAD":0,"ALBUM":0,"GOAL":0,"MESSAGE":0},
                                    "snapshotRevision":"1790901000000","serverTime":"2026-10-02T09:30:00"}}
                                    """))),
            @ApiResponse(responseCode = "400", description = "허용되지 않는 필터 FCM_4002 또는 잘못된 커서 FCM_4003",
                    content = @Content(schema = @Schema(implementation = ApiResponseTemplate.class),
                            examples = {
                                    @ExampleObject(name = "필터 오류",
                                            value = "{\"code\":\"FCM_4002\",\"message\":\"사용할 수 없는 알림센터 필터입니다.\"}"),
                                    @ExampleObject(name = "커서 오류",
                                            value = "{\"code\":\"FCM_4003\",\"message\":\"유효하지 않은 알림센터 커서입니다.\"}")
                            }))
    })
    ApiResponseTemplate<NotificationCenterResponse> list(String filter, String cursor);

    @Operation(summary = "알림 한 건 읽음 처리",
            description = "현재 수신자의 알림만 처리하며 이미 읽은 항목도 원래 readAt을 유지하고 200을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "읽음 처리 성공",
                    content = @Content(schema = @Schema(implementation = ApiResponseTemplate.class),
                            examples = @ExampleObject(value = """
                                    {"code":"200","message":"OK","data":{"notificationId":4072,
                                    "readAt":"2026-10-02T09:31:00"}}
                                    """))),
            @ApiResponse(responseCode = "404", description = "없는 알림 또는 타인의 알림 FCM_4041",
                    content = @Content(schema = @Schema(implementation = ApiResponseTemplate.class),
                            examples = @ExampleObject(value = "{\"code\":\"FCM_4041\",\"message\":\"FCM 알림이 존재하지 않습니다.\"}")))
    })
    ApiResponseTemplate<NotificationReadResponse> markAsRead(Long id);
}
