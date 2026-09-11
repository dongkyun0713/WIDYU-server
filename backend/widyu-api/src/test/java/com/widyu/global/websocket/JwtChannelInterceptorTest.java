package com.widyu.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willThrow;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.global.security.PrincipalDetails;
import com.widyu.member.MemberRole;
import com.widyu.member.application.FamilyAccessService;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtChannelInterceptor SUBSCRIBE 인가 단위 테스트")
class JwtChannelInterceptorTest {

    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private FamilyAccessService familyAccessService;

    @InjectMocks
    private JwtChannelInterceptor jwtChannelInterceptor;

    @Test
    @DisplayName("비보호 목적지 구독 시 메시지를 통과시킨다")
    void 비보호_목적지_구독_시_메시지를_통과시킨다() {
        // given
        Message<?> message = buildSubscribeMessage("/queue/errors", 100L);

        // when
        Message<?> result = jwtChannelInterceptor.preSend(message, null);

        // then
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("가족으로 연결된 보호자가 위치 topic 구독 시 메시지를 통과시킨다")
    void 가족_보호자가_위치_topic_구독_시_메시지를_통과시킨다() {
        // given
        Message<?> message = buildSubscribeMessage("/topic/location/senior/42", 100L);

        // when
        Message<?> result = jwtChannelInterceptor.preSend(message, null);

        // then
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("가족으로 연결되지 않은 보호자가 위치 topic 구독 시 null을 반환한다")
    void 비가족_보호자가_위치_topic_구독_시_null을_반환한다() {
        // given
        Message<?> message = buildSubscribeMessage("/topic/location/senior/42", 100L);
        willThrow(new BusinessException(ErrorCode.FORBIDDEN, "가족으로 연결된 시니어만 접근할 수 있습니다."))
                .given(familyAccessService).verifyFamilyAccess(anyLong(), anyLong());

        // when
        Message<?> result = jwtChannelInterceptor.preSend(message, null);

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("가족으로 연결되지 않은 보호자가 심박수 topic 구독 시 null을 반환한다")
    void 비가족_보호자가_심박수_topic_구독_시_null을_반환한다() {
        // given
        Message<?> message = buildSubscribeMessage("/topic/heart-rate/42", 100L);
        willThrow(new BusinessException(ErrorCode.FORBIDDEN, "가족으로 연결된 시니어만 접근할 수 있습니다."))
                .given(familyAccessService).verifyFamilyAccess(anyLong(), anyLong());

        // when
        Message<?> result = jwtChannelInterceptor.preSend(message, null);

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("인증 정보 없이 위치 topic 구독 시 null을 반환한다")
    void 인증_정보_없이_위치_topic_구독_시_null을_반환한다() {
        // given
        Message<?> message = buildSubscribeMessageWithNoAuth("/topic/location/senior/42");

        // when
        Message<?> result = jwtChannelInterceptor.preSend(message, null);

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("세션 속성으로 인증된 보호자가 위치 topic 구독 시 메시지를 통과시킨다")
    void 세션_속성_인증_보호자가_위치_topic_구독_시_메시지를_통과시킨다() {
        // given
        Message<?> message = buildSubscribeMessageWithSessionAttrs("/topic/location/senior/42", 100L);

        // when
        Message<?> result = jwtChannelInterceptor.preSend(message, null);

        // then
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("와일드카드 위치 topic 구독 시 가족 검증 없이 null을 반환한다")
    void 와일드카드_위치_topic_구독_시_null을_반환한다() {
        // given
        Message<?> message = buildSubscribeMessage("/topic/location/senior/*", 100L);

        // when
        Message<?> result = jwtChannelInterceptor.preSend(message, null);

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("와일드카드 심박수 topic 구독 시 가족 검증 없이 null을 반환한다")
    void 와일드카드_심박수_topic_구독_시_null을_반환한다() {
        // given
        Message<?> message = buildSubscribeMessage("/topic/heart-rate/*", 100L);

        // when
        Message<?> result = jwtChannelInterceptor.preSend(message, null);

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Long 범위를 벗어난 대상 ID 구독 시 null을 반환한다")
    void Long_범위를_벗어난_대상_ID_구독_시_null을_반환한다() {
        // given
        Message<?> message = buildSubscribeMessage("/topic/heart-rate/99999999999999999999", 100L);

        // when
        Message<?> result = jwtChannelInterceptor.preSend(message, null);

        // then
        assertThat(result).isNull();
    }

    private Message<?> buildSubscribeMessage(String destination, Long subscriberId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSessionId("test-session");
        accessor.setLeaveMutable(true);

        PrincipalDetails principal = new PrincipalDetails(subscriberId, MemberRole.USER);
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        accessor.setUser(auth);

        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> buildSubscribeMessageWithNoAuth(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSessionId("test-session");
        accessor.setLeaveMutable(true);

        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> buildSubscribeMessageWithSessionAttrs(String destination, Long subscriberId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSessionId("test-session");
        accessor.setLeaveMutable(true);

        Map<String, Object> sessionAttrs = new HashMap<>();
        sessionAttrs.put("memberId", subscriberId);
        accessor.setSessionAttributes(sessionAttrs);

        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
