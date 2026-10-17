package com.widyu.global.websocket;

import com.widyu.global.security.ActiveMemberValidator;
import com.widyu.member.MemberRole;
import java.util.Map;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * WebSocket 핸드셰이크를 30초 일회용 토큰({@code ?token=})으로만 인증한다. HTTP {@code Authorization} JWT는 받지 않는다(LLD-0088).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final WsTokenService wsTokenService;
    private final ActiveMemberValidator activeMemberValidator;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        return authenticateWithWsToken(extractTokenFromQuery(request.getURI().getQuery()), attributes);
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
    }

    private boolean authenticateWithWsToken(String tokenId, Map<String, Object> attributes) {
        if (tokenId == null) {
            log.warn("WebSocket handshake 실패 - WS 토큰 없음");
            return false;
        }

        try {
            String decodedTokenId = URLDecoder.decode(tokenId, StandardCharsets.UTF_8.name());
            Long memberId = wsTokenService.validateAndConsume(decodedTokenId);
            if (memberId == null) {
                log.warn("WebSocket handshake 실패 - WS 토큰 만료 또는 존재하지 않음");
                return false;
            }
            if (!activeMemberValidator.isActive(memberId)) {
                log.warn("WebSocket handshake 실패 - 비활성 회원 memberId: {}", memberId);
                return false;
            }
            attributes.put("memberId", memberId);
            attributes.put("memberRole", MemberRole.USER);
            log.info("WebSocket handshake 성공 (WS 토큰) - memberId: {}", memberId);
            return true;
        } catch (UnsupportedEncodingException e) {
            log.error("WebSocket handshake 실패 - WS 토큰 디코딩 오류", e);
            return false;
        }
    }

    private String extractTokenFromQuery(String query) {
        if (query == null) {
            return null;
        }
        for (String param : query.split("&")) {
            if (param.startsWith("token=")) {
                return param.substring(6);
            }
        }
        return null;
    }
}
