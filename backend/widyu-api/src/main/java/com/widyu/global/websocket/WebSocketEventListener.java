package com.widyu.global.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketSessionRegistry webSocketSessionRegistry;

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        log.info("새로운 WebSocket 연결 - sessionId: {}", event.getMessage().getHeaders().get("simpSessionId"));
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        // 끊김 원인(1002·1009·1011 등)을 운영 로그로 가르기 위해 종료 코드·사유를 남긴다.
        log.info("WebSocket 연결 종료 - sessionId: {}, closeStatus: {}", event.getSessionId(), event.getCloseStatus());
        webSocketSessionRegistry.remove(event.getSessionId());
    }

    @EventListener
    public void handleWebSocketSubscribeListener(SessionSubscribeEvent event) {
        String destination = (String) event.getMessage().getHeaders().get("simpDestination");
        log.info("새로운 구독 - destination: {}", destination);
    }
}
