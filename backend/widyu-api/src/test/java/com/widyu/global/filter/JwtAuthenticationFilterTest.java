package com.widyu.global.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

import com.widyu.global.security.JwtTokenProvider;
import com.widyu.admin.validator.AdminAccessValidator;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
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
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtTokenProvider, adminAccessValidator);
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
}
