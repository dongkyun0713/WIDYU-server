package com.widyu.global.websocket;

import static com.widyu.global.constant.SecurityConstant.TOKEN_PREFIX;

import com.widyu.auth.dto.AccessTokenDto;
import com.widyu.global.error.BusinessException;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.global.security.PrincipalDetails;
import com.widyu.member.application.FamilyAccessService;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtChannelInterceptor implements ChannelInterceptor {

    // 대상 ID는 1~18자리 숫자만 허용한다. 18자리는 항상 Long 범위 안이라 parseLong 오버플로가 없다.
    private static final Pattern LOCATION_TOPIC = Pattern.compile("^/topic/location/senior/(\\d{1,18})$");
    private static final Pattern HEART_RATE_TOPIC = Pattern.compile("^/topic/heart-rate/(\\d{1,18})$");

    // 보호 토픽 접두사. 이 접두사로 시작하지만 정확한 숫자 대상이 아니면(와일드카드 등) 구독을 거부한다.
    private static final List<String> PROTECTED_TOPIC_PREFIXES = List.of(
            "/topic/heart-rate/",
            "/topic/location/senior/"
    );

    private final JwtTokenProvider jwtTokenProvider;
    private final FamilyAccessService familyAccessService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            return handleConnect(message, accessor);
        }

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return handleSubscribe(message, accessor);
        }

        return message;
    }

    private Message<?> handleConnect(Message<?> message, StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");

        if (authHeader != null && authHeader.startsWith(TOKEN_PREFIX)) {
            String token = authHeader.replace(TOKEN_PREFIX, "");
            AccessTokenDto accessTokenDto = jwtTokenProvider.retrieveAccessToken(token);

            if (accessTokenDto != null && accessTokenDto.memberId() != null) {
                PrincipalDetails principal = new PrincipalDetails(
                        accessTokenDto.memberId(),
                        accessTokenDto.memberRole()
                );
                Authentication auth = new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()
                );
                accessor.setUser(auth);
                log.info("WebSocket CONNECT 인증 성공 - memberId: {}", accessTokenDto.memberId());
            }
        }

        return message;
    }

    private Message<?> handleSubscribe(Message<?> message, StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            return message;
        }

        if (!isProtectedTopic(destination)) {
            return message;
        }

        Long targetMemberId = extractProtectedMemberId(destination);
        if (targetMemberId == null) {
            // 보호 토픽 접두사이지만 정확한 숫자 대상이 아님(와일드카드·비숫자·초과 길이) → 가족 검증을 우회하지 못하게 차단
            log.warn("WebSocket SUBSCRIBE 거부 - 보호 토픽의 대상 ID가 유효하지 않음, destination: {}", destination);
            return null;
        }

        Long subscriberId = resolveSubscriberId(accessor);
        if (subscriberId == null) {
            log.warn("WebSocket SUBSCRIBE 인가 실패 - 인증 정보 없음, destination: {}", destination);
            return null;
        }

        try {
            familyAccessService.verifyFamilyAccess(subscriberId, targetMemberId);
            log.info("WebSocket SUBSCRIBE 인가 성공 - subscriberId: {}, destination: {}", subscriberId, destination);
        } catch (BusinessException e) {
            log.warn("WebSocket SUBSCRIBE 인가 거부 - subscriberId: {}, destination: {}", subscriberId, destination);
            return null;
        }

        return message;
    }

    private boolean isProtectedTopic(String destination) {
        return PROTECTED_TOPIC_PREFIXES.stream().anyMatch(destination::startsWith);
    }

    private Long extractProtectedMemberId(String destination) {
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

    private Long resolveSubscriberId(StompHeaderAccessor accessor) {
        java.security.Principal user = accessor.getUser();
        if (user instanceof UsernamePasswordAuthenticationToken auth
                && auth.getPrincipal() instanceof PrincipalDetails principal) {
            return principal.getMemberId();
        }

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null && sessionAttributes.get("memberId") instanceof Long memberId) {
            return memberId;
        }

        return null;
    }
}
