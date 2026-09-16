package com.widyu.fcm.controller.docs;

import com.widyu.fcm.dto.request.SendNotificationRequest;
import com.widyu.fcm.dto.response.FcmCategoryResponse;
import com.widyu.fcm.dto.response.FcmNotificationResponses;
import com.widyu.fcm.dto.response.ToastResDto;
import com.widyu.global.response.ApiResponseTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@Tag(name = "FCM", description = "푸시 메시지 및 알림 API")
public interface FcmDocs {

    @Operation(
            summary = "알림 목록 조회",
            description = "현재 로그인한 유저의 알림 목록을 카테고리별 및 커서 기반으로 조회합니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "알림 목록 조회 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "200",
                                      "message": "OK",
                                      "data": {
                                        "notifications": [
                                          {
                                            "notificationId": 1,
                                            "image": "~",
                                            "category": "ALBUM",
                                            "title": "부모님이 올려두신 게시물을 모두 읽었어요!",
                                            "content": "새로운 게시물을 업로드해주세요",
                                            "createdAt": "2025-08-26T14:00:00",
                                            "scheme": "gbableappcare://~"
                                          }
                                        ],
                                        "hasNext": true,
                                        "nextCursor": 123
                                      }
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<FcmNotificationResponses> getNotification(String category, Long cursor);

    @Operation(
            summary = "개별 알림 읽음 처리",
            description = "알림 ID를 기준으로 특정 알림을 읽음 상태로 변경합니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "알림 읽음 처리 완료",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = {
                            @ExampleObject(
                                    name = "성공 - 읽음 처리",
                                    description = "알림을 성공적으로 읽음 처리한 경우",
                                    value = """
                                            {
                                              "code": "200",
                                              "message": "OK",
                                              "data": "알림 읽음 처리 성공"
                                            }
                                            """
                            ),
                            @ExampleObject(
                                    name = "성공 - 이미 읽음",
                                    description = "이미 읽은 알림인 경우",
                                    value = """
                                            {
                                              "code": "200",
                                              "message": "OK",
                                              "data": "이미 읽은 알림입니다"
                                            }
                                            """
                            )
                    }
            )
    )
    ApiResponseTemplate<String> markAsRead(Long notificationId);

    @Operation(
            summary = "알림 카테고리 조회",
            description = "알림 카테고리별 개수를 조회합니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "알림 카테고리 조회 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "FCM_2005",
                                      "message": "알림 카테고리 조회 성공",
                                      "data": [
                                        {
                                          "label": "ALL",
                                          "name": "전체",
                                          "count": 5
                                        },
                                        {
                                          "label": "ALBUM",
                                          "name": "앨범",
                                          "count": 3
                                        }
                                      ]
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<List<FcmCategoryResponse>> getNotificationCategories();

    @Operation(
            summary = "토스트 알림 조회",
            description = "현재 로그인한 유저의 토스트 알림을 조회합니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "토스트 알림 조회 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "FCM_2006",
                                      "message": "OK",
                                      "data": {
                                        "title": "김아빠님께서 보실 소식이 2개밖에 남지 않았어요. ",
                                        "content": "새로운 게시물을 전해주세요!.",
                                        "scheme": ""
                                      }
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<ToastResDto> getToastNotification();

    @Operation(
            summary = "상대방에게 응원 알림 전송",
            description = "특정 사용자에게 푸시 알림 발송 요청을 접수합니다. 업무 커밋 후 비동기로 발송하며, 성공 응답은 FCM 전송 완료나 기기 수신을 보장하지 않습니다. 알림 제목은 서버에서 '{보내는사람}님이 {받는사람}님에게 응원메시지를 보냈어요.' 형식으로 자동 생성됩니다. 알림 카테고리는 ETC로 자동 설정되며, 발신자의 프로필 이미지가 자동으로 포함됩니다."
    )
    @RequestBody(
            description = "알림 전송 요청 정보",
            required = true,
            content = @Content(
                    schema = @Schema(implementation = SendNotificationRequest.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "receiverId": 123,
                                      "content": "오늘도 좋은 하루 보내세요~"
                                    }
                                    """
                    )
            )
    )
    @ApiResponse(
            responseCode = "200",
            description = "알림 발송 요청 접수",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "FCM_2007",
                                      "message": "알림 발송 요청을 접수했습니다",
                                      "data": "알림 발송 요청을 접수했습니다"
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<String> sendNotification(SendNotificationRequest sendNotificationRequest);
}
