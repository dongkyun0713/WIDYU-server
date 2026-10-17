package com.widyu.heart.controller;

import com.widyu.global.security.PrincipalDetails;
import com.widyu.heart.application.HeartRateService;
import com.widyu.heart.dto.request.HeartRateSingleRequest;
import com.widyu.heart.dto.response.HeartRateStatusResponse;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

@Slf4j
@Controller
@RequiredArgsConstructor
public class HeartRateWebSocketController {

    private final HeartRateService heartRateService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 시니어가 심박수 1건을 전송하는 WebSocket 엔드포인트
     * 클라이언트는 /app/heart-rate/send-single 로 메시지 전송
     * 분석 결과는 보호자 토픽과 발신 세션 ACK로 전달한다 (LLD-0023)
     */
    @MessageMapping("/heart-rate/send-single")
    @Timed(value = "heart.websocket.processing", extraTags = {"path", "single"})
    public void sendHeartRate(
            @Valid @Payload HeartRateSingleRequest request,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        String sessionId = headerAccessor.getSessionId();
        Long memberId = resolveMemberId(headerAccessor.getUser());

        HeartRateStatusResponse response = heartRateService.processHeartRate(memberId, request);

        broadcast(memberId, sessionId, response);
    }

    private void broadcast(Long memberId, String sessionId, HeartRateStatusResponse response) {
        String topic = String.format("/topic/heart-rate/%d", memberId);
        messagingTemplate.convertAndSend(topic, response);
        messagingTemplate.convertAndSendToUser(
                memberId.toString(),
                "/queue/heart-rate/result",
                response,
                sessionHeader(sessionId)
        );
    }

    /**
     * STOMP 요청의 회원은 CONNECT가 핸드셰이크 회원으로 등록한 Principal로만 식별한다(LLD-0088).
     */
    private Long resolveMemberId(Principal user) {
        if (user instanceof Authentication authentication
                && authentication.getPrincipal() instanceof PrincipalDetails principal) {
            return principal.getMemberId();
        }
        throw new IllegalStateException("인증 정보가 없습니다. WebSocket 연결 시 유효한 JWT 토큰이 필요합니다.");
    }

    /**
     * 발신 세션 헤더. {@code toMap()} 같은 일반 Map을 넘기면 SimpMessagingTemplate이 모든 키를 STOMP 네이티브 헤더로 옮겨
     * 세션 ID가 사라지고, 같은 회원의 모든 세션으로 ACK가 퍼진다. accessor가 붙은 MessageHeaders를 넘겨 그대로 쓰게 한다(LLD-0088).
     */
    private MessageHeaders sessionHeader(String sessionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);
        return accessor.getMessageHeaders();
    }
}
