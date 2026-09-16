package com.widyu.global.websocket;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class WebSocketSessionRegistry {

    private final Map<String, Long> memberIdsBySessionId = new ConcurrentHashMap<>();

    public void register(String sessionId, Long memberId) {
        if (sessionId == null || memberId == null) {
            return;
        }
        memberIdsBySessionId.put(sessionId, memberId);
    }

    public Optional<Long> findMemberId(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(memberIdsBySessionId.get(sessionId));
    }

    public void remove(String sessionId) {
        if (sessionId == null) {
            return;
        }
        memberIdsBySessionId.remove(sessionId);
    }
}
