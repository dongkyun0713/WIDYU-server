package com.widyu.auth.api;

import com.widyu.auth.dto.request.EmailCheckRequest;
import com.widyu.auth.dto.request.LocalGuardianSignupRequest;
import com.widyu.auth.dto.request.SmsCodeRequest;
import com.widyu.auth.dto.request.SmsVerificationRequest;
import com.widyu.auth.dto.response.TemporaryTokenResponse;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.global.response.ApiResponseTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;

@Tag(name = "Auth", description = "인증/회원가입 API")
public interface AuthDocs {

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
            summary = "이메일 중복 검사",
            description = "회원가입 시 입력한 이메일이 이미 등록되어 있는지 확인합니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "검사 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "성공 응답",
                            value = """
                                {
                                  "code": "AUTH_2001",
                                  "message": "이메일 등록 여부 확인 완료",
                                  "data": false
                                }
                                """
                    )
            )
    )
    ApiResponseTemplate<Boolean> isEmailRegistered(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "중복 여부 확인할 이메일",
                    content = @Content(
                            schema = @Schema(implementation = EmailCheckRequest.class),
                            examples = @ExampleObject(
                                    name = "요청 예시",
                                    value = """
                                        {
                                          "email": "widyu123"
                                        }
                                        """
                            )
                    )
            ) final EmailCheckRequest request
    );


    @Operation(
            summary = "로컬 보호자 회원가입",
            description = """
                    SMS 인증 후 발급받은 임시 토큰을 Authorization 헤더(스킴: `Bearer`)로 전달해야 합니다.
                    임시 토큰이 유효하면 이메일/비밀번호로 로컬 계정을 생성하고 Access/Refresh 토큰을 발급합니다.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "가입 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "성공 응답",
                            value = """
                                    {
                                      "code": "AUTH_2002",
                                      "message": "로컬 가디언 회원가입이 성공적으로 완료되었습니다.",
                                      "data": {
                                        "accessToken": "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                                        "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                                      }
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<TokenPairResponse> localGuardianSignup(
            HttpServletRequest httpServletRequest,
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "이메일/비밀번호/이름/전화번호",
                    content = @Content(
                            schema = @Schema(implementation = LocalGuardianSignupRequest.class),
                            examples = @ExampleObject(
                                    name = "요청 예시",
                                    value = """
                                            {
                                              "email": "widyu123",
                                              "password": "Test@1234",
                                              "name": "홍길동",
                                              "phoneNumber": "01012345678"
                                            }
                                            """
                            )
                    )
            ) final LocalGuardianSignupRequest request
    );
}
