package com.widyu.global.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import com.widyu.auth.dto.AccessTokenDto;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.ActiveMemberValidator;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.admin.validator.AdminAccessValidator;
import com.widyu.member.MemberRole;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("JWT 인증 필터 단위 테스트")
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private AdminAccessValidator adminAccessValidator;

    @Mock
    private ActiveMemberValidator activeMemberValidator;

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/auth/guardians/sign-up/local",
            "/api/v1/auth/guardians/password",
            "/api/v1/auth/guardians/apple/phone-number",
            "/api/v1/auth/guardians/profile/temporary",
            "/api/v1/auth/guardians/social/integration"
    })
    @DisplayName("임시 토큰 API를 호출하면 액세스 토큰 검증을 건너뛴다")
    void 임시_토큰_API를_호출하면_액세스_토큰_검증을_건너뛴다(String requestUri)
            throws IOException, ServletException {
        // given
        JwtAuthenticationFilter filter =
                new JwtAuthenticationFilter(jwtTokenProvider, adminAccessValidator, activeMemberValidator);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", requestUri);
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer temporary-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        // when
        filter.doFilter(request, response, filterChain);

        // then
        assertThat(filterChain.getRequest()).isSameAs(request);
        then(jwtTokenProvider).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("탈퇴·정지된 회원의 토큰으로 요청하면 401 AUTH_4020을 반환하고 다음 필터로 넘기지 않는다")
    void 비활성_회원의_토큰으로_요청하면_401을_반환한다() throws IOException, ServletException {
        // given
        JwtAuthenticationFilter filter =
                new JwtAuthenticationFilter(jwtTokenProvider, adminAccessValidator, activeMemberValidator);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/mypage");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer access-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();
        given(jwtTokenProvider.retrieveAccessToken("access-token")).willReturn(new AccessTokenDto(7L, MemberRole.USER, "local", "access-token"));
        willThrow(new BusinessException(ErrorCode.INACTIVE_MEMBER)).given(activeMemberValidator).validate(7L);

        // when
        filter.doFilter(request, response, filterChain);

        // then
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("AUTH_4020");
        assertThat(filterChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("활성 회원의 토큰으로 요청하면 인증한 뒤 다음 필터로 넘긴다")
    void 활성_회원의_토큰으로_요청하면_다음_필터로_넘긴다() throws IOException, ServletException {
        // given
        JwtAuthenticationFilter filter =
                new JwtAuthenticationFilter(jwtTokenProvider, adminAccessValidator, activeMemberValidator);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/mypage");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer access-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();
        given(jwtTokenProvider.retrieveAccessToken("access-token"))
                .willReturn(new AccessTokenDto(7L, MemberRole.USER, "local", "access-token"));

        // when
        filter.doFilter(request, response, filterChain);

        // then
        then(activeMemberValidator).should().validate(7L);
        assertThat(filterChain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("관리자 토큰은 기존 관리자 확인만 거치고 활성 회원 확인을 추가로 하지 않는다")
    void 관리자_토큰은_활성_회원_확인을_하지_않는다() throws IOException, ServletException {
        // given
        JwtAuthenticationFilter filter =
                new JwtAuthenticationFilter(jwtTokenProvider, adminAccessValidator, activeMemberValidator);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/members");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer admin-token");
        MockFilterChain filterChain = new MockFilterChain();
        given(jwtTokenProvider.retrieveAccessToken("admin-token")).willReturn(new AccessTokenDto(1L, MemberRole.ADMIN, "local", "admin-token"));

        // when
        filter.doFilter(request, new MockHttpServletResponse(), filterChain);

        // then
        then(adminAccessValidator).should().validateMemberId(1L);
        then(activeMemberValidator).shouldHaveNoInteractions();
        assertThat(filterChain.getRequest()).isSameAs(request);
    }
}
