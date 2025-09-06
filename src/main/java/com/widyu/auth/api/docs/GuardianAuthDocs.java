package com.widyu.auth.api.docs;

import com.widyu.auth.dto.request.AppleSignUpRequest;
import com.widyu.auth.dto.request.ChangePasswordRequest;
import com.widyu.auth.dto.request.EmailCheckRequest;
import com.widyu.auth.dto.request.LocalGuardianSignInRequest;
import com.widyu.auth.dto.request.LocalGuardianSignupRequest;
import com.widyu.auth.dto.request.SmsVerificationRequest;
import com.widyu.auth.dto.request.SocialLoginRequest;
import com.widyu.auth.dto.response.MemberInfoResponse;
import com.widyu.auth.dto.response.SocialLoginResponse;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.auth.dto.response.UserProfile;
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
import jakarta.validation.Valid;
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
            summary = "소셜 로그인",
            description = """
                    프론트엔드에서 발급받은 소셜 액세스 토큰 또는 Apple 인증 정보를 사용하여 로그인합니다.
                    소셜 토큰으로 사용자 정보를 조회한 후, 서비스 토큰 페어(Access/Refresh)를 발급합니다.
                    
                    • Kakao/Naver: accessToken 필드 사용
                    • Apple: authorizationCode 및 profile 필드 사용
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
                                        "isFirst": false,
                                        "tokenPair": {
                                          "accessToken": "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                                          "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                                        }
                                      }
                                    }
                                    """
                    )
            )
    )
    @ApiResponse(
            responseCode = "400",
            description = "잘못된 요청 (토큰 누락/유효하지 않음 등)",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "토큰 오류",
                            value = """
                                    {
                                      "code": "AUTH_4001",
                                      "message": "유효하지 않은 액세스 토큰입니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<SocialLoginResponse> signInSocial(
            @Parameter(
                    name = "provider",
                    description = "소셜 제공자 식별자 (naver, kakao, apple)",
                    in = ParameterIn.QUERY,
                    required = true,
                    examples = {
                        @ExampleObject(name = "네이버", value = "naver"), 
                        @ExampleObject(name = "카카오", value = "kakao"),
                        @ExampleObject(name = "애플", value = "apple")
                    }
            )
            String provider,
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "소셜 로그인 정보 (Kakao/Naver: accessToken, Apple: authorizationCode + profile)",
                    content = @Content(
                            schema = @Schema(implementation = SocialLoginRequest.class),
                            examples = {
                                @ExampleObject(
                                        name = "Kakao/Naver 요청",
                                        value = """
                                                {
                                                  "accessToken": "AAAA1234567890abcdef..."
                                                }
                                                """
                                ),
                                @ExampleObject(
                                        name = "Apple 요청 (최초 로그인)",
                                        value = """
                                                {
                                                  "authorizationCode": "abc123",
                                                  "profile": {
                                                    "email": "user@icloud.com",
                                                    "name": "홍길동"
                                                  }
                                                }
                                                """
                                ),
                                @ExampleObject(
                                        name = "Apple 요청 (재로그인)",
                                        value = """
                                                {
                                                  "authorizationCode": "abc123",
                                                  "profile": {
                                                    "email": null,
                                                    "name": null
                                                  }
                                                }
                                                """
                                )
                            }
                    )
            ) final SocialLoginRequest request
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

    @Operation(
            summary = "애플 로그인 회원 전화번호 업데이트",
            description = """
                    애플 로그인 사용자가 회원가입 후 전화번호를 업데이트합니다.
                    JWT 토큰이 필요하며, 현재 로그인된 사용자의 전화번호를 업데이트합니다.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "전화번호 업데이트 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "성공 응답",
                            value = """
                                    {
                                      "code": "AUTH_2008",
                                      "message": "애플 로그인 회원 전화번호 업데이트 성공",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    @ApiResponse(
            responseCode = "401",
            description = "인증 실패(토큰 만료/없음/권한 오류)",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "토큰 오류",
                            value = """
                                    {
                                      "code": "AUTH_4001",
                                      "message": "유효하지 않은 액세스 토큰입니다.",
                                      "data": null
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<Void> updatePhoneNumberIfAppleSignUp(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "애플 사용자 이메일",
                    content = @Content(
                            schema = @Schema(implementation = AppleSignUpRequest.class),
                            examples = @ExampleObject(
                                    name = "요청 예시",
                                    value = """
                                            {
                                              "email": "user@icloud.com"
                                            }
                                            """
                            )
                    )
            ) final AppleSignUpRequest request,
            HttpServletRequest httpServletRequest
    );

    @Operation(
            summary = "자체 회원가입 계정 유무 확인",
            description = """
                    SMS 인증 후 발급받은 임시 토큰을 사용하여 사용자 프로필 정보를 조회합니다.
                    Authorization 헤더(스킴: `Bearer`)로 임시 토큰을 전달해야 합니다.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "프로필 조회 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "성공 응답",
                            value = """
                                    {
                                      "code": "AUTH_2009",
                                      "message": "임시 토큰으로 사용자 프로필 조회 성공",
                                      "data": {
                                        "name": "김민지",
                                        "phoneNumber": "010-1234-5678",
                                        "email": "abc@abc.com",
                                        "providers": ["kakao"]
                                      }
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
    ApiResponseTemplate<UserProfile> getUserProfileByTemporaryToken(
            HttpServletRequest httpServletRequest
    );
}
