package com.widyu.auth.api.docs;

import com.widyu.auth.dto.request.ParentSignInRequest;
import com.widyu.auth.dto.request.ParentSignUpRequest;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.global.response.ApiResponseTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;

@Tag(name = "Auth - Parents", description = "부모님 인증/회원가입 API")
public interface ParentAuthDocs {

    @Operation(
            summary = "학부모 회원가입(배치) - 초대코드",
            description = """
                    보호자가 발급한 **초대코드(숫자 7자리)**로 학부모 계정을 **여러 명 한 번에** 생성합니다.
                    각 항목은 이름/생년월일(YYYYMMDD)/전화번호/주소/상세주소(선택)/초대코드를 포함합니다.
                    요청 리스트 내 중복 초대코드 또는 DB에 이미 존재하는 초대코드는 거절됩니다.
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
                                      "code": "AUTH_2007",
                                      "message": "로컬 학부모 회원가입이 성공적으로 완료되었습니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    @ApiResponse(
            responseCode = "400",
            description = "요청 내 중복 초대코드 혹은 잘못된 요청",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = {
                            @ExampleObject(
                                    name = "요청 내 중복 초대코드",
                                    value = """
                                            {
                                              "code": "REQ_4000",
                                              "message": "잘못된 요청입니다. : 요청 내 중복 초대코드=[\\"1234567\\"]",
                                              "data": null
                                            }
                                            """
                            )
                    }
            )
    )
    @ApiResponse(
            responseCode = "400",
            description = "DB에 이미 존재하는 초대코드",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "DB 중복 초대코드",
                            value = """
                                    {
                                      "code": "REQ_4000",
                                      "message": "잘못된 요청입니다. : 기존에 존재하는 초대코드=[\\"7654321\\"]",
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
                    description = "학부모 회원가입 정보 리스트",
                    content = @Content(
                            array = @ArraySchema(schema = @Schema(implementation = ParentSignUpRequest.class)),
                            examples = @ExampleObject(
                                    name = "요청 예시(배치)",
                                    value = """
                                            [
                                              {
                                                "name": "김부모",
                                                "birthDate": "19750815",
                                                "phoneNumber": "01012345678",
                                                "address": "서울특별시 강남구 테헤란로 123",
                                                "detailAddress": "101동 1001호",
                                                "inviteCode": "1234567"
                                              },
                                              {
                                                "name": "이부모",
                                                "birthDate": "19800301",
                                                "phoneNumber": "01098765432",
                                                "address": "서울특별시 서초구 서초대로 45",
                                                "detailAddress": "A동 202호",
                                                "inviteCode": "7654321"
                                              }
                                            ]
                                            """
                            )
                    )
            ) final List<ParentSignUpRequest> request
    );

    @Operation(
            summary = "학부모 로그인(초대코드 + 전화번호)",
            description = """
                    **초대코드(숫자 7자리)**와 **전화번호(숫자 10~11자리)**로 로그인하여 Access/Refresh 토큰 페어를 발급합니다.
                    이메일/비밀번호 입력은 사용하지 않습니다.
                    """
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
                                      "code": "AUTH_2008",
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
    @ApiResponse(
            responseCode = "404",
            description = "초대코드 미존재",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "초대코드 없음",
                            value = """
                                    {
                                      "code": "REQ_4000",
                                      "message": "잘못된 요청입니다. : 초대코드를 찾을 수 없습니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<TokenPairResponse> signIn(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "학부모 로그인 정보(초대코드 + 전화번호)",
                    content = @Content(
                            schema = @Schema(implementation = ParentSignInRequest.class),
                            examples = @ExampleObject(
                                    name = "요청 예시",
                                    value = """
                                            {
                                              "inviteCode": "1234567",
                                              "phoneNumber": "01012345678"
                                            }
                                            """
                            )
                    )
            ) final ParentSignInRequest request
    );
}
