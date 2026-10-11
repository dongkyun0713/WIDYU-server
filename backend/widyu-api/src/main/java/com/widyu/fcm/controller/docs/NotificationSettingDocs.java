package com.widyu.fcm.controller.docs;

import com.widyu.fcm.dto.request.UpdateNotificationSettingRequest;
import com.widyu.fcm.dto.response.NotificationSettingResponse;
import com.widyu.global.response.ApiResponseTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@Tag(name = "FCM Settings", description = "본인의 그룹별 제품 푸시 설정 API")
public interface NotificationSettingDocs {

    @Operation(
            summary = "알림 설정 조회",
            description = "본인의 제품 푸시 설정을 조회합니다. 보호자는 4그룹, 시니어는 GENERAL 1그룹입니다. 기기 상태 unknown은 OS 권한을 알 수 없다는 뜻입니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "알림 설정 조회 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "FCM_2010",
                                      "message": "알림 설정 조회 성공",
                                      "data": [
                                        {
                                          "group": "SAFETY",
                                          "groupName": "안전 알림",
                                          "enabled": true,
                                          "recipientEnabled": true,
                                          "productPushEnabled": true,
                                          "mandatoryProductPush": true,
                                          "canEditProductPush": false,
                                          "devicePushStatus": "unknown",
                                          "policyRevision": 4
                                        }
                                      ]
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<List<NotificationSettingResponse>> getNotificationSettings();

    @Operation(
            summary = "알림 설정 변경",
            description = "로그인한 본인의 제품 푸시만 변경합니다. policyRevision이 최신값과 다르거나 방장이 안전 푸시를 끄면 409입니다. 푸시를 꺼도 알림센터 행은 남습니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "알림 설정 변경 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "FCM_2011",
                                      "message": "알림 설정 변경 성공",
                                      "data": {
                                        "group": "GENERAL",
                                        "groupName": "일반 알림",
                                        "enabled": false,
                                        "recipientEnabled": true,
                                        "productPushEnabled": false,
                                        "mandatoryProductPush": false,
                                        "canEditProductPush": true,
                                        "devicePushStatus": "unknown",
                                        "policyRevision": 5
                                      }
                                    }
                                    """
                    )
            )
    )
    @ApiResponse(
            responseCode = "400",
            description = "유효하지 않은 그룹 또는 요청 값",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "code": "FCM_4001",
                                      "message": "유효하지 않은 알림 카테고리입니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    @ApiResponse(
            responseCode = "409",
            description = "방장 안전 푸시 필수(FCM_4090) 또는 낡은 policyRevision(FCM_4091)"
    )
    ApiResponseTemplate<NotificationSettingResponse> updateNotificationSetting(
            @RequestBody(
                    description = "알림 설정 변경 요청",
                    required = true,
                    content = @Content(
                            schema = @Schema(implementation = UpdateNotificationSettingRequest.class),
                            examples = {
                                    @ExampleObject(
                                            name = "그룹 알림 끄기",
                                            value = """
                                                    {
                                                      "group": "GENERAL",
                                                      "enabled": false,
                                                      "policyRevision": 4
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "그룹 알림 켜기",
                                            value = """
                                                    {
                                                      "group": "GENERAL",
                                                      "enabled": true,
                                                      "policyRevision": 4
                                                    }
                                                    """
                                    )
                            }
                    )
            ) UpdateNotificationSettingRequest request
    );
}
