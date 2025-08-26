package com.widyu.fcm.api;

import com.widyu.fcm.api.dto.FcmSendDto;
import com.widyu.global.response.ApiResponseTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;

@Tag(name = "FCM", description = "푸시 메시지 전송 API")
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
                            name = "성공 응답 예시",
                            value = """
                                    {
                                      "code": "FCM_2001",
                                      "message": "푸시 메시지 전송 성공",
                                      "data": 2
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<Integer> pushMessage(
            @RequestBody(
                    required = true,
                    description = "푸시 메시지 요청 DTO",
                    content = @Content(
                            schema = @Schema(implementation = FcmSendDto.class),
                            examples = @ExampleObject(
                                    name = "푸시 메시지 요청 예시",
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
}
