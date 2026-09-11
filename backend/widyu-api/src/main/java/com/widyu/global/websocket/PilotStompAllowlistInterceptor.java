package com.widyu.global.websocket;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * 실증(pilot)에서 STOMP SEND/SUBSCRIBE 목적지를 허용 목록으로 제한한다.
 * SEND는 알려진 목적지와 정확히 일치할 때만, SUBSCRIBE는 숫자 대상 ID 또는 개인 큐와
 * 정확히 일치할 때만 통과시킨다. 브로커(AntPathMatcher)가 {@code /topic/heart-rate/*}
 * 같은 와일드카드 구독으로 다른 가족 브로드캐스트를 수신하지 못하도록 접두사 매칭을 쓰지 않는다.
 * 인증·가족 검증은 이어지는 {@code JwtChannelInterceptor}가 수행한다.
 */
@Slf4j
@Component
@Profile("pilot")
public class PilotStompAllowlistInterceptor implements ChannelInterceptor {

    private static final Set<String> ALLOWED_SEND_DESTINATIONS = Set.of(
            "/app/heart-rate/send-single",
            "/app/location/update"
    );

    // 대상 ID는 1~18자리 숫자만 허용한다. 18자리는 항상 Long 범위 안이라,
    // 하위 JwtChannelInterceptor의 Long.parseLong 에서 NumberFormatException 이 나지 않는다.
    private static final List<Pattern> ALLOWED_SUBSCRIBE_PATTERNS = List.of(
            Pattern.compile("^/topic/heart-rate/\\d{1,18}$"),
            Pattern.compile("^/topic/location/senior/\\d{1,18}$"),
            Pattern.compile("^/user/queue/heart-rate/result$"),
            Pattern.compile("^/user/queue/location/ack$"),
            Pattern.compile("^/user/queue/errors$")
    );

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (StompCommand.SEND.equals(command)) {
            return allowSend(message, accessor.getDestination());
        }
        if (StompCommand.SUBSCRIBE.equals(command)) {
            return allowSubscribe(message, accessor.getDestination());
        }

        return message;
    }

    private Message<?> allowSend(Message<?> message, String destination) {
        if (destination != null && ALLOWED_SEND_DESTINATIONS.contains(destination)) {
            return message;
        }
        return blocked(destination);
    }

    private Message<?> allowSubscribe(Message<?> message, String destination) {
        if (destination == null) {
            return blocked(null);
        }

        boolean allowed = ALLOWED_SUBSCRIBE_PATTERNS.stream()
                .anyMatch(pattern -> pattern.matcher(destination).matches());
        if (allowed) {
            return message;
        }
        return blocked(destination);
    }

    private Message<?> blocked(String destination) {
        log.warn("실증 STOMP 허용 목록 위반으로 차단 - destination: {}", destination);
        return null;
    }
}
