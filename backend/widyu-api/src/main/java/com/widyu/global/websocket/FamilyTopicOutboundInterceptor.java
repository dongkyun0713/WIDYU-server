package com.widyu.global.websocket;

import com.widyu.member.application.FamilyAccessService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FamilyTopicOutboundInterceptor implements ChannelInterceptor {

    private static final Pattern LOCATION_TOPIC = Pattern.compile("^/topic/location/senior/(\\d{1,18})$");
    private static final Pattern HEART_RATE_TOPIC = Pattern.compile("^/topic/heart-rate/(\\d{1,18})$");

    private final FamilyAccessService familyAccessService;
    private final WebSocketSessionRegistry webSocketSessionRegistry;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
        Long targetMemberId = extractProtectedMemberId(destination);
        if (targetMemberId == null) {
            return message;
        }

        String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        Long subscriberId = webSocketSessionRegistry.findMemberId(sessionId).orElse(null);
        if (subscriberId == null) {
            log.warn("WebSocket OUTBOUND 거부 - 인증 세션 없음, sessionId: {}, destination: {}", sessionId, destination);
            return null;
        }

        try {
            familyAccessService.verifyActiveFamilyAccess(subscriberId, targetMemberId);
            return message;
        } catch (RuntimeException e) {
            log.warn("WebSocket OUTBOUND 거부 - subscriberId: {}, destination: {}, cause: {}",
                    subscriberId, destination, e.getClass().getSimpleName());
            return null;
        }
    }

    private Long extractProtectedMemberId(String destination) {
        if (destination == null) {
            return null;
        }

        Matcher locationMatcher = LOCATION_TOPIC.matcher(destination);
        if (locationMatcher.matches()) {
            return Long.parseLong(locationMatcher.group(1));
        }

        Matcher heartMatcher = HEART_RATE_TOPIC.matcher(destination);
        if (heartMatcher.matches()) {
            return Long.parseLong(heartMatcher.group(1));
        }

        return null;
    }
}
