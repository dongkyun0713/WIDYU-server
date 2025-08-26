package com.widyu.fcm.api;

import com.widyu.fcm.api.dto.FcmSendDto;
import com.widyu.fcm.api.dto.response.FcmNotificationResponses;
import com.widyu.fcm.api.dto.response.FcmSendResponse;
import com.widyu.global.response.ApiResponseTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.io.IOException;

@Tag(name = "FCM", description = "푸시 메시지 및 알림 API")
public interface FcmDocs {

    @Operation(
            summary = "푸시 메시지 전송",
            description = "현재 로그인한 유저의 모든 디바이스 토큰으로 푸시 메시지를 전송합니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "푸시 메시지 전송 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "FCM_2001",
                                      "message": "푸시 메시지 전송 성공",
                                      "data": {
                                        "title": "새 공지사항",
                                        "body": "새로운 공지사항이 등록되었습니다.",
                                        "successCount": 2
                                      }
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<FcmSendResponse> pushMessage(
            @RequestBody(
                    required = true,
                    description = "푸시 메시지 요청 DTO",
                    content = @Content(
                            schema = @Schema(implementation = FcmSendDto.class),
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "title": "새 공지사항",
                                              "body": "새로운 공지사항이 등록되었습니다."
                                            }
                                            """
                            )
                    )
            )
            FcmSendDto fcmSendDto
    ) throws IOException;

    @Operation(
            summary = "알림 목록 조회",
            description = "현재 로그인한 유저의 알림 목록을 최신순으로 조회합니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "알림 목록 조회 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "FCM_2002",
                                      "message": "사용자 알림 조회 성공",
                                      "data": {
                                        "notifications": [
                                          {
                                            "id": 1,
                                            "title": "새 댓글",
                                            "body": "홍길동님이 댓글을 남겼습니다.",
                                            "isRead": false,
                                            "createdAt": "2025-08-26T14:00:00"
                                          },
                                          {
                                            "id": 2,
                                            "title": "공지사항",
                                            "body": "서버 점검이 예정되어 있습니다.",
                                            "isRead": true,
                                            "createdAt": "2025-08-25T10:00:00"
                                          }
                                        ]
                                      }
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<FcmNotificationResponses> getNotification();

    @Operation(
            summary = "전체 알림 읽음 처리",
            description = "현재 로그인한 유저의 모든 알림을 읽음 상태로 변경합니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "전체 알림 읽음 처리 완료",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "FCM_2003",
                                      "message": "전체 알림 읽음 처리 완료",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<Void> markAllAsRead();

    @Operation(
            summary = "개별 알림 읽음 처리",
            description = "알림 ID를 기준으로 특정 알림을 읽음 상태로 변경합니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "알림 읽음 처리 완료",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "FCM_2004",
                                      "message": "알림 읽음 처리 완료",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<Void> markAsRead(Long notificationId);
}
