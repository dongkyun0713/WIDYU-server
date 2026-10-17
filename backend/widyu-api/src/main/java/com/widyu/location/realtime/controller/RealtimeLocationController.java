package com.widyu.location.realtime.controller;

import com.widyu.global.security.PrincipalDetails;
import com.widyu.location.realtime.application.RealtimeLocationService;
import com.widyu.location.realtime.dto.LocationUpdateResponse;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

@Slf4j
@Controller
@RequiredArgsConstructor
public class RealtimeLocationController {

    private final RealtimeLocationService realtimeLocationService;

    /**
     * 시니어가 위치 업데이트를 전송하는 엔드포인트
     * 클라이언트는 /app/location/update 로 메시지 전송
     *
     * <p>본문을 DTO가 아니라 원문 바이트로 받는다. v2 페이로드는 원문 그대로 보관해야 하므로
     * (LLD-0048 3절) 재직렬화 지점을 두지 않는다. 파싱·검증은 서비스가 한다.
     */
    @MessageMapping("/location/update")
    @SendToUser(destinations = "/queue/location/ack", broadcast = false)
    public LocationUpdateResponse updateLocation(
            Message<byte[]> message,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        Long authenticatedMemberId = resolveMemberId(headerAccessor.getUser());
        log.info("위치 업데이트 수신 - authenticatedMemberId: {}", authenticatedMemberId);

        return realtimeLocationService.updateAndBroadcast(message.getPayload(), authenticatedMemberId);
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
