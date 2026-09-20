package com.widyu.admin.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.widyu.admin.application.AdminAccessLogService;
import com.widyu.admin.application.AdminAuthService;
import com.widyu.admin.controller.AdminAuthController;
import com.widyu.admin.repository.AdminAuditLogRepository;
import com.widyu.admin.validator.AdminAccessValidator;
import com.widyu.auth.dto.AccessTokenDto;
import com.widyu.auth.dto.RefreshTokenDto;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.auth.infrastructure.AuthLimitStore;
import com.widyu.auth.infrastructure.ClientIpResolver;
import com.widyu.global.config.SecurityConfig;
import com.widyu.global.entity.Status;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.member.Member;
import com.widyu.member.MemberRole;
import com.widyu.member.MemberType;
import com.widyu.member.repository.LocalAccountRepository;
import com.widyu.member.repository.MemberRepository;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(AdminAuthController.class)
@Import({SecurityConfig.class, AdminAccessValidator.class, AdminAuthService.class,
        AdminCurrentAuthoritySecurityTest.AdminProbeController.class})
@DisplayName("관리자 현재 권한 HTTP 보안")
class AdminCurrentAuthoritySecurityTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean private MemberRepository memberRepository;
    @MockBean private LocalAccountRepository localAccountRepository;
    @MockBean private AdminAuditLogRepository adminAuditLogRepository;
    @MockBean private AuthLimitStore authLimitStore;
    @MockBean private ClientIpResolver clientIpResolver;
    // WebMvcConfig가 등록하는 접속기록 인터셉터의 의존성. 슬라이스 테스트에는 서비스 빈이 없다.
    @MockBean private AdminAccessLogService adminAccessLogService;

    @Test
    @DisplayName("일반 회원 refresh를 관리자 쿠키에 넣으면 발급을 거절한다")
    void 일반_회원_refresh_쿠키로_관리자_토큰을_발급하지_않는다() throws Exception {
        // given
        given(jwtTokenProvider.retrieveRefreshToken("user-refresh"))
                .willReturn(new RefreshTokenDto(1L, "user-refresh", 60L));
        given(memberRepository.findById(1L)).willReturn(Optional.of(member(MemberRole.USER, Status.ACTIVE)));

        // when & then
        mockMvc.perform(post("/api/v1/auth/admin/refresh")
                        .cookie(new Cookie("admin_refresh_token", "user-refresh")))
                .andExpect(status().isForbidden());
        then(jwtTokenProvider).should(never()).generateTokenPair(any(), any(), any());
    }

    @Test
    @DisplayName("활성 관리자의 refresh 쿠키로 재발급하면 새 액세스 토큰을 반환한다")
    void 활성_관리자의_refresh_쿠키로_재발급한다() throws Exception {
        // given
        given(jwtTokenProvider.retrieveRefreshToken("admin-refresh"))
                .willReturn(new RefreshTokenDto(1L, "admin-refresh", 60L));
        given(memberRepository.findById(1L)).willReturn(Optional.of(member(MemberRole.ADMIN, Status.ACTIVE)));
        given(jwtTokenProvider.generateTokenPair(1L, MemberRole.ADMIN, "local"))
                .willReturn(TokenPairResponse.of(1L, "new-access", "new-refresh"));

        // when & then
        mockMvc.perform(post("/api/v1/auth/admin/refresh")
                        .cookie(new Cookie("admin_refresh_token", "admin-refresh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access"));
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"INACTIVE", "DELETED", "PROCESSING"})
    @DisplayName("비활성 회원의 기존 ADMIN 토큰은 관리자 API에 접근하지 못한다")
    void 비활성_관리자의_기존_토큰을_거절한다(Status memberStatus) throws Exception {
        // given
        given(jwtTokenProvider.retrieveAccessToken("admin-access"))
                .willReturn(AccessTokenDto.of(1L, MemberRole.ADMIN, "local", "admin-access"));
        given(memberRepository.findById(1L)).willReturn(Optional.of(member(MemberRole.ADMIN, memberStatus)));

        // when & then
        mockMvc.perform(get("/api/v1/admin/probe").header(HttpHeaders.AUTHORIZATION, "Bearer admin-access"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("강등된 회원은 이전 ADMIN 토큰으로 관리자 API에 접근하지 못한다")
    void 강등된_관리자의_기존_토큰을_거절한다() throws Exception {
        // given
        given(jwtTokenProvider.retrieveAccessToken("old-admin"))
                .willReturn(AccessTokenDto.of(1L, MemberRole.ADMIN, "local", "old-admin"));
        given(memberRepository.findById(1L)).willReturn(Optional.of(member(MemberRole.USER, Status.ACTIVE)));

        // when & then
        mockMvc.perform(get("/api/v1/admin/probe").header(HttpHeaders.AUTHORIZATION, "Bearer old-admin"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("삭제된 회원의 ADMIN 토큰은 관리자 API에 접근하지 못한다")
    void 삭제된_관리자의_기존_토큰을_거절한다() throws Exception {
        // given
        given(jwtTokenProvider.retrieveAccessToken("deleted-admin"))
                .willReturn(AccessTokenDto.of(1L, MemberRole.ADMIN, "local", "deleted-admin"));
        given(memberRepository.findById(1L)).willReturn(Optional.empty());

        // when & then
        mockMvc.perform(get("/api/v1/admin/probe").header(HttpHeaders.AUTHORIZATION, "Bearer deleted-admin"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("일반 USER 토큰은 관리자 API에 접근하지 못한다")
    void 일반_USER_토큰의_관리자_API_접근을_거절한다() throws Exception {
        // given
        given(jwtTokenProvider.retrieveAccessToken("user-access"))
                .willReturn(AccessTokenDto.of(1L, MemberRole.USER, "local", "user-access"));

        // when & then
        mockMvc.perform(get("/api/v1/admin/probe").header(HttpHeaders.AUTHORIZATION, "Bearer user-access"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("활성 관리자의 ADMIN 토큰은 관리자 API에 접근한다")
    void 활성_관리자의_API_접근을_허용한다() throws Exception {
        // given
        given(jwtTokenProvider.retrieveAccessToken("admin-access"))
                .willReturn(AccessTokenDto.of(1L, MemberRole.ADMIN, "local", "admin-access"));
        given(memberRepository.findById(1L)).willReturn(Optional.of(member(MemberRole.ADMIN, Status.ACTIVE)));

        // when & then
        mockMvc.perform(get("/api/v1/admin/probe").header(HttpHeaders.AUTHORIZATION, "Bearer admin-access"))
                .andExpect(status().isOk())
                .andExpect(content().string("allowed"));
    }

    private Member member(MemberRole role, Status memberStatus) {
        Member member = Member.createMember(MemberType.GUARDIAN, "테스트", "01000000000");
        ReflectionTestUtils.setField(member, "id", 1L);
        ReflectionTestUtils.setField(member, "role", role);
        ReflectionTestUtils.setField(member, "status", memberStatus);
        return member;
    }

    @RestController
    static class AdminProbeController {
        @GetMapping("/api/v1/admin/probe")
        public String probe() {
            return "allowed";
        }
    }
}
