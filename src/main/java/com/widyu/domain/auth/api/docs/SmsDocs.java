package com.widyu.domain.auth.api.docs;

import com.widyu.domain.auth.dto.request.FindPasswordRequest;
import com.widyu.domain.auth.dto.request.SmsCodeRequest;
import com.widyu.domain.auth.dto.request.SmsVerificationRequest;
import com.widyu.domain.auth.dto.response.TemporaryTokenResponse;
import com.widyu.global.response.ApiResponseTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;

@Tag(name = "Sms", description = "문자 인증 API")
public interface SmsDocs {
    @Operation(
            summary = "SMS 인증번호 전송",
            description = "사용자의 이름과 전화번호를 받아 인증번호를 전송합니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "전송 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "성공 응답",
                            value = """
                                    {
                                      "code": "SMS_2001",
                                      "message": "문자가 성공적으로 전송되었습니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<Void> sendSmsVerification(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "이름과 전화번호",
                    content = @Content(
                            schema = @Schema(implementation = SmsVerificationRequest.class),
                            examples = @ExampleObject(
                                    name = "요청 예시",
                                    value = """
                                            {
                                              "name": "홍길동",
                                              "phoneNumber": "01012345678"
                                            }
                                            """
                            )
                    )
            ) final SmsVerificationRequest request
    );

    @Operation(
            summary = "SMS 인증번호 검증",
            description = "전화번호와 인증코드를 검증하고, 성공 시 30분 유효의 임시 토큰을 발급합니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "검증 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "성공 응답",
                            value = """
                                    {
                                      "code": "SMS_2002",
                                      "message": "SMS 인증이 성공적으로 완료되었습니다.",
                                      "data": {
                                        "temporaryToken": "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                                      }
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<TemporaryTokenResponse> verifySmsCode(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "전화번호와 인증코드",
                    content = @Content(
                            schema = @Schema(implementation = SmsCodeRequest.class),
                            examples = @ExampleObject(
                                    name = "요청 예시",
                                    value = """
                                            {
                                              "phoneNumber": "01012345678",
                                              "code": "123456"
                                            }
                                            """
                            )
                    )
            ) final SmsCodeRequest request
    );

    @Operation(
            summary = "비밀번호 찾기용 SMS 인증번호 전송",
            description = """
                    비밀번호 재설정 시도 시, 입력받은 이름/이메일/전화번호 조합이 실제 회원과 일치하는 경우에만
                    인증번호를 발송합니다.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "전송 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "성공 응답",
                            value = """
                                    {
                                      "code": "SMS_2003",
                                      "message": "문자가 성공적으로 전송되었습니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    @ApiResponse(
            responseCode = "404",
            description = "회원 없음",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "실패 응답",
                            value = """
                                    {
                                      "code": "MEMBER_4041",
                                      "message": "해당 정보와 일치하는 회원을 찾을 수 없습니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<Void> sendSmsVerificationIfMemberExist(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "비밀번호 재설정 시 확인할 이름/이메일/전화번호",
                    content = @Content(
                            schema = @Schema(implementation = FindPasswordRequest.class),
                            examples = @ExampleObject(
                                    name = "요청 예시",
                                    value = """
                                            {
                                              "name": "홍길동",
                                              "email": "user@example.com",
                                              "phoneNumber": "01012345678"
                                            }
                                            """
                            )
                    )
            ) final FindPasswordRequest request
    );
}

