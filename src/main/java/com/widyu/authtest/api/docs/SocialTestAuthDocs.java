package com.widyu.authtest.api.docs;

import com.widyu.authtest.dto.response.SocialLoginResponse;
import com.widyu.global.response.ApiResponseTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Tag(name = "Social-Test", description = "소셜 로그인 테스트 API")
public interface SocialTestAuthDocs {

    @Operation(
            summary = "소셜 로그인 시작 (네이버, 카카오)",
            description = """
                    provider에 'naver'를 전달하면 네이버 로그인 페이지로 302 리다이렉트합니다.
                    Swagger UI(iframe) 환경에서는 보안 정책으로 리다이렉트가 실패할 수 있으니, 실제 테스트는 브라우저 주소창에서 호출하세요.
                    """
    )
    @ApiResponse(
            responseCode = "302",
            description = "네이버 로그인 페이지로 리다이렉트",
            content = @Content(
                    mediaType = "text/html",
                    examples = @ExampleObject(
                            name = "리다이렉트 예시",
                            value = "<html><script>location.replace(\"https://nid.naver.com/oauth2.0/authorize?... \")</script></html>"
                    )
            )
    )
    ApiResponseTemplate<String> signInSocial(
            @Parameter(
                    name = "provider",
                    description = "소셜 제공자 식별자 (현재 'naver' 지원)",
                    in = ParameterIn.QUERY,
                    required = true,
                    examples = {@ExampleObject(name = "네이버", value = "naver")}
            )
            String provider,
            HttpServletResponse response
    ) throws IOException;

    @Operation(
            summary = "소셜 로그인 콜백",
            description = """
                    네이버에서 인증 후 전달하는 콜백입니다.
                    쿼리 파라미터로 전달된 code/state를 검증하고, 서비스 토큰 페어(Access/Refresh)를 발급합니다.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "소셜 로그인 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "성공 응답",
                            value = """
                                    {
                                      "code": "AUTH_2004",
                                      "message": "소셜 로그인 성공",
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
            responseCode = "400",
            description = "잘못된 요청 (state 누락/불일치 등)",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "state 오류",
                            value = """
                                    {
                                      "code": "AUTH_4003",
                                      "message": "유효하지 않은 OAuth state입니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<SocialLoginResponse> socialLoginCallback(
            @Parameter(
                    name = "provider",
                    description = "소셜 제공자 식별자 (현재 'naver' 지원)",
                    in = ParameterIn.PATH,
                    required = true,
                    examples = @ExampleObject(name = "네이버", value = "naver")
            )
            String provider,
            @Parameter(
                    name = "code",
                    description = "네이버 인가 코드",
                    in = ParameterIn.QUERY,
                    required = true,
                    examples = @ExampleObject(name = "예시", value = "A1B2C3...")
            )
            String code,
            @Parameter(
                    name = "state",
                    description = "CSRF 방지용 state (서버에서 생성 후 Redis에 저장된 값과 일치해야 함)",
                    in = ParameterIn.QUERY,
                    required = true,
                    examples = @ExampleObject(name = "예시", value = "ZxY123...")
            )
            String state
    );
}
