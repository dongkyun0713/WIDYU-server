package com.widyu.auth.api.docs;

import com.widyu.auth.dto.request.EmailCheckRequest;
import com.widyu.auth.dto.request.LocalGuardianSignInRequest;
import com.widyu.auth.dto.request.LocalGuardianSignupRequest;
import com.widyu.auth.dto.request.SmsVerificationRequest;
import com.widyu.auth.dto.request.ChangePasswordRequest;
import com.widyu.auth.dto.response.MemberInfoResponse;
import com.widyu.auth.dto.response.SocialLoginResponse;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.global.response.ApiResponseTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.web.bind.annotation.RequestBody;

@Tag(name = "Auth - Guardians", description = "보호자 인증/회원가입 API")
public interface GuardianAuthDocs {

    @Operation(
            summary = "이메일 중복 검사(보호자)",
            description = "보호자 회원가입 시 이메일이 이미 등록되어 있는지 확인합니다."
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
                                              "email": "user@example.com"
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
                                      "message": "로컬 보호자 회원가입이 성공적으로 완료되었습니다.",
                                      "data": {
                                        "accessToken": "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                                        "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                                      }
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<TokenPairResponse> signupLocal(
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
                                              "email": "user@example.com",
                                              "password": "Test@1234",
                                              "name": "홍길동",
                                              "phoneNumber": "01012345678"
                                            }
                                            """
                            )
                    )
            ) final LocalGuardianSignupRequest request
    );

    @Operation(
            summary = "로컬 보호자 로그인",
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
                                      "code": "AUTH_2003",
                                      "message": "로컬 보호자 로그인 성공",
                                      "data": {
                                        "accessToken": "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                                        "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                                      }
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<TokenPairResponse> signInLocal(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "이메일/비밀번호",
                    content = @Content(
                            schema = @Schema(implementation = LocalGuardianSignInRequest.class),
                            examples = @ExampleObject(
                                    name = "요청 예시",
                                    value = """
                                            {
                                              "email": "user@example.com",
                                              "password": "Test@1234"
                                            }
                                            """
                            )
                    )
            ) final LocalGuardianSignInRequest request
    );

    @Operation(
            summary = "소셜 로그인 시작 (네이버)",
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

    @Operation(
            summary = "전화번호로 회원 이메일 조회",
            description = """
                    이름과 휴대폰 번호를 검증 후, 해당 회원의 이메일 정보를 반환합니다.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "조회 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "성공 응답",
                            value = """
                                    {
                                      "code": "AUTH_2006",
                                      "message": "휴대폰 번호로 회원 조회 성공",
                                      "data": {
                                        "name": "홍길동",
                                        "phoneNumber": "01012345678",
                                        "email": "user@example.com"
                                      }
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<MemberInfoResponse> findMemberByPhoneNumber(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "이름/전화번호/인증코드(정책에 따라 포함)",
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
            summary = "비밀번호 변경(임시 토큰 필요)",
            description = """
                    SMS 본인확인 후 발급된 임시 토큰(Authorization: Bearer)을 사용하여 비밀번호를 변경합니다.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "비밀번호 변경 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "성공 응답",
                            value = """
                                    {
                                      "code": "AUTH_2007",
                                      "message": "비밀번호 변경 성공",
                                      "data": true
                                    }
                                    """
                    )
            )
    )
    @ApiResponse(
            responseCode = "401",
            description = "인증 실패(임시 토큰 만료/없음/권한 오류)",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "토큰 만료",
                            value = """
                                    {
                                      "code": "AUTH_4011",
                                      "message": "임시 토큰이 만료되었습니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<Boolean> changePassword(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "새 비밀번호/확인 비밀번호",
                    content = @Content(
                            schema = @Schema(implementation = ChangePasswordRequest.class),
                            examples = @ExampleObject(
                                    name = "요청 예시",
                                    value = """
                                            {
                                              "password": "NewPass@1234",
                                              "confirmPassword": "NewPass@1234"
                                            }
                                            """
                            )
                    )
            ) final ChangePasswordRequest request,
            HttpServletRequest httpServletRequest
    );
}
