package com.widyu.global.websocket;

import com.widyu.global.security.MemberSessionService;
import com.widyu.member.application.FamilyAccessService;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Component
@RequiredArgsConstructor
public class WsSessionGuard implements ExecutorChannelInterceptor {
    private static final Pattern FAMILY_TOPIC = Pattern.compile("^/topic/(?:location/senior|heart-rate)/(\\d{1,18})$");
    private final MemberSessionService memberSessionService;
    private final FamilyAccessService familyAccessService;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void register(WebSocketSession session) {
        sessions.put(session.getId(), session);
        if (!current(session.getAttributes())) {
            close(session.getId());
        }
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        SimpMessageType type = SimpMessageHeaderAccessor.getMessageType(message.getHeaders());
        if (type == SimpMessageType.DISCONNECT || type == SimpMessageType.DISCONNECT_ACK) {
            return message;
        }
        String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        if (sessionId == null || !sessions.containsKey(sessionId)) {
            return null;
        }
        // ExecutorSubscribableChannel은 동기 executor에서도 beforeHandle을 실행한다.
        // DB 인가는 큐 대기가 끝난 뒤 각 handler 실행 직전에 수행한다.
        return message;
    }

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        return check(message);
    }

    private Message<?> check(Message<?> message) {
        SimpMessageType type = SimpMessageHeaderAccessor.getMessageType(message.getHeaders());
        if (type == SimpMessageType.DISCONNECT || type == SimpMessageType.DISCONNECT_ACK) {
            return message;
        }
        String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        if (sessionId == null) {
            return null;
        }
        WebSocketSession session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        if (!current(session.getAttributes())) {
            close(sessionId);
            return null;
        }
        String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
        if (destination != null) {
            var matcher = FAMILY_TOPIC.matcher(destination);
            if (matcher.matches()) {
                try {
                    familyAccessService.verifyFamilyAccess((Long) session.getAttributes().get("memberId"),
                            Long.valueOf(matcher.group(1)));
                } catch (RuntimeException e) {
                    close(sessionId);
                    return null;
                }
            }
        }
        return message;
    }

    private boolean current(Map<String, Object> attributes) {
        if (!(attributes.get("memberId") instanceof Long memberId)
                || !(attributes.get("authVersion") instanceof Long version)) {
            return false;
        }
        try {
            return memberSessionService.isCurrent(memberId, version);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @TransactionalEventListener
    public void revoked(MemberSessionService.SessionsRevoked event) {
        sessions.forEach((id, session) -> {
            if (event.memberId().equals(session.getAttributes().get("memberId"))
                    && !current(session.getAttributes())) {
                close(id);
            }
        });
    }

    @Scheduled(fixedDelay = 1000)
    public void closeRevokedSessions() {
        sessions.forEach((id, session) -> {
            if (!current(session.getAttributes())) {
                close(id);
            }
        });
    }

    private void close(String sessionId) {
        WebSocketSession session = sessions.remove(sessionId);
        if (session != null) {
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (IOException e) {
                log.warn("폐기된 WebSocket 연결 종료 실패: {}", sessionId);
            }
        }
    }
}
