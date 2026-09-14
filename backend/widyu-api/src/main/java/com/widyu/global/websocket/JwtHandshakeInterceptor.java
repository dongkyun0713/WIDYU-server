package com.widyu.global.websocket;

import static com.widyu.global.constant.SecurityConstant.TOKEN_PREFIX;

import com.widyu.auth.dto.AccessTokenDto;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.member.MemberRole;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtTokenProvider jwtTokenProvider;
    private final WsTokenService wsTokenService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || authHeader.isEmpty()) {
            String query = request.getURI().getQuery();
            if (query != null && query.contains("token=")) {
                return authenticateWithWsToken(extractTokenFromQuery(query), attributes);
            }
        }

        return authenticateWithJwt(authHeader, attributes);
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
            WsTokenService.WsSessionIdentity identity = wsTokenService.consumeIdentity(decodedTokenId);
            if (identity == null) {
                log.warn("WebSocket handshake 실패 - WS 토큰 만료 또는 존재하지 않음");
                return false;
            }
            Long memberId = identity.memberId();
            attributes.put("memberId", memberId);
            attributes.put("authVersion", identity.authVersion());
            attributes.put("memberRole", MemberRole.USER);
            log.info("WebSocket handshake 성공 (WS 토큰) - memberId: {}", memberId);
            return true;
        } catch (UnsupportedEncodingException e) {
            log.error("WebSocket handshake 실패 - WS 토큰 디코딩 오류", e);
            return false;
        }
    }

    private boolean authenticateWithJwt(String authHeader, Map<String, Object> attributes) {
        if (authHeader == null || !authHeader.startsWith(TOKEN_PREFIX)) {
            log.warn("WebSocket handshake 실패 - Authorization 헤더 없음");
            return false;
        }

        String token = authHeader.replace(TOKEN_PREFIX, "");
        AccessTokenDto accessTokenDto = jwtTokenProvider.retrieveAccessToken(token);

        if (accessTokenDto == null) {
            log.warn("WebSocket handshake 실패 - 유효하지 않은 JWT");
            return false;
        }

        attributes.put("memberId", accessTokenDto.memberId());
        attributes.put("authVersion", accessTokenDto.authVersion());
        attributes.put("memberRole", accessTokenDto.memberRole());
        log.info("WebSocket handshake 성공 (JWT) - memberId: {}", accessTokenDto.memberId());
        return true;
    }

    private String extractTokenFromQuery(String query) {
        for (String param : query.split("&")) {
            if (param.startsWith("token=")) {
                return param.substring(6);
            }
        }
        return null;
    }
}
