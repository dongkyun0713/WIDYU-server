package com.widyu.location.realtime.controller;

import com.widyu.global.security.PrincipalDetails;
import com.widyu.location.realtime.application.RealtimeLocationService;
import com.widyu.location.realtime.dto.LocationUpdateRequest;
import com.widyu.location.realtime.dto.LocationUpdateResponse;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

@Slf4j
@Controller
@RequiredArgsConstructor
public class RealtimeLocationController {

    private final RealtimeLocationService realtimeLocationService;

    /**
     * 시니어가 위치 업데이트를 전송하는 엔드포인트
     * 클라이언트는 /app/location/update 로 메시지 전송
     */
    @MessageMapping("/location/update")
    @SendToUser("/queue/location/ack")
    public LocationUpdateResponse updateLocation(
            @Valid @Payload LocationUpdateRequest request,
            @AuthenticationPrincipal PrincipalDetails principal,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        Long authenticatedMemberId = resolveMemberId(principal, headerAccessor);
        log.info("위치 업데이트 수신 - authenticatedMemberId: {}, requestMemberId: {}",
                 authenticatedMemberId, request.memberId());

        return realtimeLocationService.updateAndBroadcast(request, authenticatedMemberId);
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
}
