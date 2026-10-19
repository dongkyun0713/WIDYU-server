package com.widyu.heart.controller;

import com.widyu.global.security.PrincipalDetails;
import com.widyu.global.websocket.WebSocketWorkDispatcher;
import com.widyu.heart.application.HeartRateService;
import com.widyu.heart.dto.request.HeartRateSingleRequest;
import com.widyu.heart.dto.response.HeartRateStatusResponse;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

@Slf4j
@Controller
@RequiredArgsConstructor
public class HeartRateWebSocketController {

    private static final String API = "HeartRateWebSocketController.sendHeartRate";
    private static final String ACK_DESTINATION = "/queue/heart-rate/result";

    private final HeartRateService heartRateService;
    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketWorkDispatcher workDispatcher;
    private final TaskExecutor websocketHeartExecutor;
    private final MeterRegistry meterRegistry;

    /**
     * 시니어가 심박수 1건을 전송하는 WebSocket 엔드포인트
     * 클라이언트는 /app/heart-rate/send-single 로 메시지 전송
     * 분석 결과는 보호자 토픽과 발신 세션 ACK로 전달한다 (LLD-0023)
     * AI 호출이 최대 10초 걸리므로 심박 전용 실행기에서 처리하고 수신 스레드는 바로 돌려준다 (LLD-0091)
     */
    @MessageMapping("/heart-rate/send-single")
    public void sendHeartRate(
            @Valid @Payload HeartRateSingleRequest request,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        Long memberId = resolveMemberId(headerAccessor.getUser());
        String sessionId = headerAccessor.getSessionId();

        workDispatcher.dispatch(websocketHeartExecutor, API, headerAccessor,
                () -> meterRegistry.timer("heart.websocket.processing", "path", "single")
                        .record(() -> process(memberId, sessionId, request)));
    }

    private void process(Long memberId, String sessionId, HeartRateSingleRequest request) {
        HeartRateStatusResponse response = heartRateService.processHeartRate(memberId, request);
        messagingTemplate.convertAndSend(String.format("/topic/heart-rate/%d", memberId), response);
        workDispatcher.sendToSession(memberId.toString(), sessionId, ACK_DESTINATION, response);
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
}
