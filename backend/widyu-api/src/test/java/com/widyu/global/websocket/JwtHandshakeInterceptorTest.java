package com.widyu.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.widyu.auth.dto.AccessTokenDto;
import com.widyu.global.security.ActiveMemberValidator;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.member.MemberRole;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocket 핸드셰이크 인증 단위 테스트")
class JwtHandshakeInterceptorTest {

    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private WsTokenService wsTokenService;
    @Mock private ActiveMemberValidator activeMemberValidator;

    @InjectMocks private JwtHandshakeInterceptor interceptor;

    @Test
    @DisplayName("비활성 회원의 JWT로 연결하면 핸드셰이크를 거부한다")
    void 비활성_회원의_JWT로_연결하면_핸드셰이크를_거부한다() {
        // given
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws/location");
        servletRequest.addHeader(HttpHeaders.AUTHORIZATION, "Bearer access-token");
        given(jwtTokenProvider.retrieveAccessToken("access-token"))
                .willReturn(new AccessTokenDto(7L, MemberRole.USER, "local", "access-token"));
        given(activeMemberValidator.isActive(7L)).willReturn(false);
        Map<String, Object> attributes = new HashMap<>();

        // when
        boolean accepted = handshake(servletRequest, attributes);

        // then
        assertThat(accepted).isFalse();
        assertThat(attributes).isEmpty();
    }

    @Test
    @DisplayName("비활성 회원의 일회용 WS 토큰으로 연결하면 핸드셰이크를 거부한다")
    void 비활성_회원의_WS_토큰으로_연결하면_핸드셰이크를_거부한다() {
        // given
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws/location");
        servletRequest.setQueryString("token=ws-token");
        given(wsTokenService.validateAndConsume("ws-token")).willReturn(7L);
        given(activeMemberValidator.isActive(7L)).willReturn(false);
        Map<String, Object> attributes = new HashMap<>();

        // when
        boolean accepted = handshake(servletRequest, attributes);

        // then
        assertThat(accepted).isFalse();
        assertThat(attributes).isEmpty();
    }

    @Test
    @DisplayName("활성 회원의 JWT로 연결하면 회원 정보를 세션 속성에 담아 핸드셰이크를 허용한다")
    void 활성_회원의_JWT로_연결하면_핸드셰이크를_허용한다() {
        // given
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws/location");
        servletRequest.addHeader(HttpHeaders.AUTHORIZATION, "Bearer access-token");
        given(jwtTokenProvider.retrieveAccessToken("access-token"))
                .willReturn(new AccessTokenDto(7L, MemberRole.USER, "local", "access-token"));
        given(activeMemberValidator.isActive(7L)).willReturn(true);
        Map<String, Object> attributes = new HashMap<>();

        // when
        boolean accepted = handshake(servletRequest, attributes);

        // then
        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry("memberId", 7L).containsEntry("memberRole", MemberRole.USER);
    }

    private boolean handshake(MockHttpServletRequest servletRequest, Map<String, Object> attributes) {
        return interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                mock(WebSocketHandler.class),
                attributes);
    }
}
