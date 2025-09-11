package com.widyu.domain.fcm.api;

import com.widyu.domain.fcm.api.dto.request.FcmTokenLoginRequest;
import com.widyu.domain.fcm.api.dto.request.FcmTokenLogoutRequest;
import com.widyu.global.response.ApiResponseTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "FCM Token", description = "FCM 토큰 등록 및 해제 API")
public interface MemberFcmTokenDocs {

    @Operation(
            summary = "로그인 시 FCM 토큰 저장",
            description = "사용자가 로그인할 때 디바이스의 FCM 토큰을 서버에 등록합니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "FCM 토큰 저장 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "응답 예시",
                            value = """
                                    {
                                      "code": "FCM_2002",
                                      "message": "로그인 시 FCM 토큰 처리 완료",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<Void> saveFcmToken(
            @RequestBody(
                    required = true,
                    description = "FCM 토큰과 디바이스 정보",
                    content = @Content(
                            schema = @Schema(implementation = FcmTokenLoginRequest.class),
                            examples = @ExampleObject(
                                    name = "요청 예시",
                                    value = """
                                            {
                                              "token": "fcm_token_string",
                                              "deviceInfo": "iPhone 13 - iOS 17"
                                            }
                                            """
                            )
                    )
            )
            FcmTokenLoginRequest fcmTokenLoginRequest
    );

    @Operation(
            summary = "로그아웃 시 FCM 토큰 비활성화",
            description = "사용자가 로그아웃할 때 해당 디바이스의 FCM 토큰을 비활성화합니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "FCM 토큰 비활성화 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "응답 예시",
                            value = """
                                    {
                                      "code": "FCM_2003",
                                      "message": "로그아웃 시 FCM 토큰 비활성화 완료",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<Void> deactivateFcmToken(
            @RequestBody(
                    required = true,
                    description = "비활성화할 FCM 토큰",
                    content = @Content(
                            schema = @Schema(implementation = FcmTokenLogoutRequest.class),
                            examples = @ExampleObject(
                                    name = "요청 예시",
                                    value = """
                                            {
                                              "token": "fcm_token_string"
                                            }
                                            """
                            )
                    )
            )
            FcmTokenLogoutRequest fcmTokenLogoutRequest
    );
}
