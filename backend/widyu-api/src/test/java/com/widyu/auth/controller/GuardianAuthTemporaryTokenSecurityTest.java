package com.widyu.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.widyu.auth.application.guardian.GuardianAuthService;
import com.widyu.admin.application.AdminAccessLogService;
import com.widyu.admin.validator.AdminAccessValidator;
import com.widyu.auth.dto.request.LocalGuardianSignupRequest;
import com.widyu.auth.dto.response.LocalSignupResponse;
import com.widyu.auth.dto.response.SignUpUserInfo;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.global.config.SecurityConfig;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GuardianAuthController.class)
@Import(SecurityConfig.class)
@DisplayName("보호자 임시 토큰 HTTP 인증")
class GuardianAuthTemporaryTokenSecurityTest {

    private static final String TEMPORARY_TOKEN = "temporary-token";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GuardianAuthService guardianAuthService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private AdminAccessValidator adminAccessValidator;

    // WebMvcConfig가 등록하는 접속기록 인터셉터의 의존성. 슬라이스 테스트에는 서비스 빈이 없다.
    @MockBean
    private AdminAccessLogService adminAccessLogService;

    @Test
    @DisplayName("임시 토큰으로 이메일 회원가입하면 서비스까지 요청을 전달한다")
    void 임시_토큰으로_이메일_회원가입하면_서비스까지_요청을_전달한다() throws Exception {
        // given
        LocalSignupResponse response = LocalSignupResponse.ofTokenPair(
                TokenPairResponse.of(1L, "access-token", "refresh-token"),
                SignUpUserInfo.of("홍길동", "01012345678", "guardian@example.com")
        );
        given(jwtTokenProvider.retrieveAccessToken(TEMPORARY_TOKEN))
                .willThrow(new BusinessException(ErrorCode.INVALID_ACCESS_TOKEN));
        given(guardianAuthService.localGuardianSignup(
                any(HttpServletRequest.class),
                any(LocalGuardianSignupRequest.class)
        )).willReturn(response);

        // when & then
        mockMvc.perform(post("/api/v1/auth/guardians/sign-up/local")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TEMPORARY_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "guardian@example.com",
                                  "password": "Password1!",
                                  "name": "홍길동",
                                  "phoneNumber": "01012345678"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("AUTH_2002"));

        then(guardianAuthService).should().localGuardianSignup(
                any(HttpServletRequest.class),
                any(LocalGuardianSignupRequest.class)
        );
    }

    @Test
    @DisplayName("일반 API에 유효하지 않은 액세스 토큰을 전달하면 기존 오류를 반환한다")
    void 일반_API에_유효하지_않은_액세스_토큰을_전달하면_기존_오류를_반환한다() throws Exception {
        // given
        String invalidAccessToken = "invalid-access-token";
        given(jwtTokenProvider.retrieveAccessToken(invalidAccessToken))
                .willThrow(new BusinessException(ErrorCode.INVALID_ACCESS_TOKEN));

        // when & then
        mockMvc.perform(post("/api/v1/ws/token")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + invalidAccessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_4018"));
    }
}
