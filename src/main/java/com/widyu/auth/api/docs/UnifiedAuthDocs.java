package com.widyu.auth.api.docs;

import com.widyu.auth.dto.request.LogoutRequest;
import com.widyu.auth.dto.request.RefreshTokenRequest;
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

@Tag(name = "Auth - Unified", description = "토큰 재발급 & 로그아웃 API")
public interface UnifiedAuthDocs {

    @Operation(
            summary = "토큰 재발급",
            description = """
                    유효한 **리프레시 토큰**을 제출하면 새로운 **액세스/리프레시 토큰 페어**를 발급합니다.
                    서버 저장소에 존재하지 않거나 위·변조된 리프레시 토큰은 거절됩니다.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "재발급 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "성공 응답",
                            value = """
                                    {
                                      "code": "AUTH_2009",
                                      "message": "토큰 재발급에 성공했습니다.",
                                      "data": {
                                        "accessToken": "Bearer eyJhbGciOiJIUzI1NiIs...",
                                        "refreshToken": "eyJhbGciOiJIUzI1NiIs..."
                                      }
                                    }
                                    """
                    )
            )
    )
    @ApiResponse(
            responseCode = "401",
            description = "유효하지 않은 리프레시 토큰",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "리프레시 토큰 오류",
                            value = """
                                    {
                                      "code": "AUTH_4013",
                                      "message": "리프레시 토큰이 유효하지 않습니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<TokenPairResponse> reissueTokenPair(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "리프레시 토큰",
                    content = @Content(
                            schema = @Schema(implementation = RefreshTokenRequest.class),
                            examples = @ExampleObject(
                                    name = "요청 예시",
                                    value = """
                                            {
                                              "refreshToken": "eyJhbGciOiJIUzI1NiIs..."
                                            }
                                            """
                            )
                    )
            ) final RefreshTokenRequest request
    );

    @Operation(
            summary = "로그아웃",
            description = """
                    **리프레시 토큰을 서버 저장소에서 삭제**하여 재발급을 차단합니다.
                    (선택) 클라이언트는 로컬에 보관 중인 액세스/리프레시 토큰을 함께 제거하세요.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "로그아웃 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "성공 응답",
                            value = """
                                    {
                                      "code": "AUTH_2010",
                                      "message": "로그아웃에 성공했습니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    @ApiResponse(
            responseCode = "401",
            description = "유효하지 않은 리프레시 토큰",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "리프레시 토큰 오류",
                            value = """
                                    {
                                      "code": "AUTH_4013",
                                      "message": "리프레시 토큰이 유효하지 않습니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<Void> logout(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "로그아웃 요청(리프레시 토큰)",
                    content = @Content(
                            schema = @Schema(implementation = LogoutRequest.class),
                            examples = @ExampleObject(
                                    name = "요청 예시",
                                    value = """
                                            {
                                              "refreshToken": "eyJhbGciOiJIUzI1NiIs..."
                                            }
                                            """
                            )
                    )
            ) final LogoutRequest request
    );
}
