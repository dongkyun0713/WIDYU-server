package com.widyu.heart.controller;

import com.widyu.global.security.PrincipalDetails;
import com.widyu.heart.application.HeartRateService;
import com.widyu.heart.dto.request.HeartRateSendRequest;
import com.widyu.heart.dto.request.HeartRateSingleRequest;
import com.widyu.heart.dto.response.HeartRateStatusResponse;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

@Slf4j
@Controller
@RequiredArgsConstructor
public class HeartRateWebSocketController {

    private final HeartRateService heartRateService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 시니어가 심박수 데이터(15개)를 전송하는 WebSocket 엔드포인트
     * 클라이언트는 /app/heart-rate/send 로 메시지 전송
     * 분석 결과는 /topic/heart-rate/{memberId} 로 보호자에게 브로드캐스트
     * ACK는 /user/queue/heart-rate/result 로 전송자에게 수신
     */
    @MessageMapping("/heart-rate/send")
    @Timed(value = "heart.websocket.processing", extraTags = {"path", "batch"})
    public void sendHeartRates(
            @Valid @Payload HeartRateSendRequest request,
            @AuthenticationPrincipal PrincipalDetails principal,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        String sessionId = headerAccessor.getSessionId();
        Long memberId = resolveMemberId(principal, headerAccessor);
        log.info("심박수 데이터 수신 (WebSocket) - memberId: {}, 데이터 수: {}", memberId, request.heartRates().size());

        HeartRateStatusResponse response = heartRateService.processHeartRates(memberId, request);

        broadcast(memberId, sessionId, response);
    }

    /**
     * 시니어가 심박수 1건을 전송하는 WebSocket 엔드포인트
     * 클라이언트는 /app/heart-rate/send-single 로 메시지 전송
     * 브로드캐스트와 ACK 경로는 배치 전송과 동일하다 (LLD-0023)
     */
    @MessageMapping("/heart-rate/send-single")
    @Timed(value = "heart.websocket.processing", extraTags = {"path", "single"})
    public void sendHeartRate(
            @Valid @Payload HeartRateSingleRequest request,
            @AuthenticationPrincipal PrincipalDetails principal,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        String sessionId = headerAccessor.getSessionId();
        Long memberId = resolveMemberId(principal, headerAccessor);

        HeartRateStatusResponse response = heartRateService.processHeartRate(memberId, request);

        broadcast(memberId, sessionId, response);
    }

    private void broadcast(Long memberId, String sessionId, HeartRateStatusResponse response) {
        String topic = String.format("/topic/heart-rate/%d", memberId);
        messagingTemplate.convertAndSend(topic, response);
        messagingTemplate.convertAndSendToUser(sessionId, "/queue/heart-rate/result", response, sessionHeader(sessionId));
    }

    private Long resolveMemberId(PrincipalDetails principal, SimpMessageHeaderAccessor headerAccessor) {
        if (principal != null && principal.getMemberId() != null) {
            return principal.getMemberId();
        }

        Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
        if (sessionAttributes != null && sessionAttributes.get("memberId") instanceof Long memberId) {
            log.debug("세션 속성에서 memberId 조회 - memberId: {}", memberId);
            return memberId;
        }

        throw new IllegalStateException("인증 정보가 없습니다. WebSocket 연결 시 유효한 JWT 토큰이 필요합니다.");
    }

    private Map<String, Object> sessionHeader(String sessionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);
        return accessor.toMap();
    }
}
