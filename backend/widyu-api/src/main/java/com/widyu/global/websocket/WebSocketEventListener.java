package com.widyu.global.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
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
        // 끊김 원인(1002·1006·1009·1011 등)을 운영 로그로 가르기 위해 종료 코드·사유를 남긴다.
        CloseStatus closeStatus = event.getCloseStatus();
        log.info("WebSocket 연결 종료 - sessionId: {}, closeCode: {}, closeReason: {}",
                event.getSessionId(), closeStatus.getCode(), escapeLineBreaks(closeStatus.getReason()));
        webSocketSessionRegistry.remove(event.getSessionId());
    }

    /** 종료 사유는 클라이언트가 보낸 값이라 줄바꿈으로 로그 행을 위조하지 못하게 한다. */
    static String escapeLineBreaks(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.replace("\r", "\\r").replace("\n", "\\n");
    }

    @EventListener
    public void handleWebSocketSubscribeListener(SessionSubscribeEvent event) {
        String destination = (String) event.getMessage().getHeaders().get("simpDestination");
        log.info("새로운 구독 - destination: {}", destination);
    }
}
