package com.widyu.auth.api;

import com.widyu.auth.dto.request.ParentSignInRequest;
import com.widyu.auth.dto.request.ParentSignUpRequest;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.global.response.ApiResponseTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;

@Tag(name = "Auth - Parents", description = "학부모(부모님) 인증/회원가입 API")
public interface ParentAuthDocs {

    @Operation(
            summary = "학부모 회원가입(초대코드)",
            description = """
                    보호자가 발급한 **초대코드(숫자 7자리)**로 학부모 계정을 생성합니다.
                    이름/생년월일/전화번호/주소를 함께 제출해야 합니다.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "회원가입 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "성공 응답",
                            value = """
                                    {
                                      "code": "AUTH_2005",
                                      "message": "로컬 학부모 회원가입이 성공적으로 완료되었습니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<Void> signUp(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "학부모 회원가입 정보",
                    content = @Content(
                            schema = @Schema(implementation = ParentSignUpRequest.class),
                            examples = @ExampleObject(
                                    name = "요청 예시",
                                    value = """
                                            {
                                              "name": "김부모",
                                              "birthDate": "1975-08-15",
                                              "phoneNumber": "01012345678",
                                              "address": "서울특별시 강남구 테헤란로 123",
                                              "detailAddress": "101동 1001호",
                                              "inviteCode": "1234567"
                                            }
                                            """
                            )
                    )
            ) final ParentSignUpRequest request
    );

    @Operation(
            summary = "학부모 로그인(로컬)",
            description = "이메일과 비밀번호로 로그인하여 Access/Refresh 토큰을 발급합니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "로그인 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "성공 응답",
                            value = """
                                    {
                                      "code": "AUTH_2006",
                                      "message": "로컬 학부모 로그인 성공",
                                      "data": {
                                        "accessToken": "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                                        "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                                      }
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<TokenPairResponse> signIn(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "학부모 로그인 정보",
                    content = @Content(
                            schema = @Schema(implementation = ParentSignInRequest.class),
                            examples = @ExampleObject(
                                    name = "요청 예시",
                                    value = """
                                            {
                                              "email": "parent@example.com",
                                              "password": "Test@1234"
                                            }
                                            """
                            )
                    )
            ) final ParentSignInRequest request
    );
}
