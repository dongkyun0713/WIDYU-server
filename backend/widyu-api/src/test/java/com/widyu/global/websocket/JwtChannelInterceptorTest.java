package com.widyu.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.global.security.PrincipalDetails;
import com.widyu.member.MemberRole;
import com.widyu.member.application.FamilyAccessService;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import com.widyu.auth.dto.AccessTokenDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
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

    @ParameterizedTest
    @ValueSource(strings = {"/user/queue/errors", "/user/queue/location/ack", "/user/queue/heart-rate/result"})
    @DisplayName("인증된 사용자가 ACK와 오류 큐를 구독하면 메시지를 통과시킨다")
    void 사용자_큐를_구독하면_메시지를_통과시킨다(String destination) {
        // given
        Message<?> message = buildSubscribeMessage(destination, 100L);

        // when
        Message<?> result = jwtChannelInterceptor.preSend(message, null);

        // then
        assertThat(result).isNotNull();
        verifyNoInteractions(familyAccessService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/location/senior/42", "/topic/heart-rate/42"})
    @DisplayName("가족으로 연결된 보호자가 위치 topic 구독 시 메시지를 통과시킨다")
    void 가족_보호자가_위치_topic_구독_시_메시지를_통과시킨다(String destination) {
        // given
        Message<?> message = buildSubscribeMessage(destination, 100L);

        // when
        Message<?> result = jwtChannelInterceptor.preSend(message, null);

        // then
        assertThat(result).isNotNull();
        verify(familyAccessService).verifyFamilyAccess(100L, 42L);
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

    @Test
    @DisplayName("19자리 대상 ID 구독은 차단한다")
    void 열아홉자리_대상_ID_구독은_차단한다() {
        // given
        Message<?> message = buildSubscribeMessage("/topic/heart-rate/1000000000000000000", 100L);

        // when
        Message<?> result = jwtChannelInterceptor.preSend(message, null);

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("18자리 대상 ID 구독은 가족 검증을 거쳐 통과시킨다")
    void 열여덟자리_대상_ID_구독은_통과시킨다() {
        // given
        Message<?> message = buildSubscribeMessage("/topic/heart-rate/100000000000000000", 100L);

        // when
        Message<?> result = jwtChannelInterceptor.preSend(message, null);

        // then
        assertThat(result).isNotNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"/topic/**", "/topic/*", "/**", "/topic/location/**",
            "/topic/location/senior", "/topic/location/42", "/topic/heart-rate",
            "/topic/heart-rate/42/extra", "/topic/heart-rate/42/", "/topic/heart-rate/{id}",
            "/topic/heart-rate/+42", "/topic/heart-rate/-42", "/topic/heart-rate/%34%32",
            "/topic/heart-rate/42?x=1", "/topic/unknown/42", "/queue/errors",
            "/queue/heart-rate/result-userother", "/user/42/queue/heart-rate/result",
            "/user/queue/**", "/user/queue/errors/", "/app/location/update"})
    @DisplayName("허용 목록 밖 목적지를 구독하면 채널 다음 처리기에 전달하지 않는다")
    void 허용되지_않은_구독은_전달하지_않는다(String destination) {
        // given
        Message<?> message = frame(StompCommand.SUBSCRIBE, destination, true);
        List<Message<?>> received = new ArrayList<>();
        ExecutorSubscribableChannel channel = channel(received);

        // when
        boolean sent = channel.send(message);

        // then
        assertThat(sent).isFalse();
        assertThat(received).isEmpty();
        verifyNoInteractions(familyAccessService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"/topic/location/senior/42", "/topic/heart-rate/42", "/topic/**",
            "/queue/errors", "/queue/location/ack", "/user/queue/heart-rate/result",
            "/user/42/queue/heart-rate/result", "/app/heart-rate/send",
            "/app/location/update/", "/app/location/update?x=1", "/app/**", "/app/unknown"})
    @DisplayName("브로커 또는 미등록 경로에 SEND하면 채널 다음 처리기에 전달하지 않는다")
    void 직접_발송과_미등록_SEND는_전달하지_않는다(String destination) {
        // given
        List<Message<?>> received = new ArrayList<>();
        ExecutorSubscribableChannel channel = channel(received);

        // when
        boolean sent = channel.send(frame(StompCommand.SEND, destination, true));

        // then
        assertThat(sent).isFalse();
        assertThat(received).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/app/location/update", "/app/heart-rate/send-single"})
    @DisplayName("인증된 사용자가 기존 SEND 경로로 전송하면 원본 메시지를 전달한다")
    void 기존_SEND는_원본_메시지를_전달한다(String destination) {
        // given
        Message<?> message = frame(StompCommand.SEND, destination, true);
        List<Message<?>> received = new ArrayList<>();
        ExecutorSubscribableChannel channel = channel(received);

        // when
        boolean sent = channel.send(message);

        // then
        assertThat(sent).isTrue();
        assertThat(received).containsExactly(message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/location/senior/42", "/topic/heart-rate/42",
            "/user/queue/location/ack", "/user/queue/heart-rate/result", "/user/queue/errors"})
    @DisplayName("인증 없이 허용 목적지를 구독하면 거절한다")
    void 인증_없는_구독을_거절한다(String destination) {
        // given
        Message<?> message = frame(StompCommand.SUBSCRIBE, destination, false);
        // when
        Message<?> result = jwtChannelInterceptor.preSend(message, null);
        // then
        assertThat(result).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/app/location/update", "/app/heart-rate/send-single"})
    @DisplayName("인증 없이 허용 SEND 경로로 전송하면 거절한다")
    void 인증_없는_SEND를_거절한다(String destination) {
        // given
        Message<?> message = frame(StompCommand.SEND, destination, false);
        // when
        Message<?> result = jwtChannelInterceptor.preSend(message, null);
        // then
        assertThat(result).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"ACK", "NACK", "UNSUBSCRIBE", "DISCONNECT"})
    @DisplayName("목적지 없는 제어 프레임을 받으면 그대로 통과시킨다")
    void 제어_프레임을_유지한다(StompCommand command) {
        // given
        Message<?> message = frame(command, null, false);
        // when
        Message<?> result = jwtChannelInterceptor.preSend(message, null);
        // then
        assertThat(result).isSameAs(message);
    }

    @Test
    @DisplayName("heartbeat를 받으면 그대로 통과시킨다")
    void heartbeat를_유지한다() {
        // given
        StompHeaderAccessor accessor = StompHeaderAccessor.createForHeartbeat();
        Message<?> message = MessageBuilder.createMessage(new byte[] {'\n'}, accessor.getMessageHeaders());
        // when
        Message<?> result = jwtChannelInterceptor.preSend(message, null);
        // then
        assertThat(result).isSameAs(message);
    }

    @Test
    @DisplayName("CONNECT로 인증하면 Principal로 SEND를 허용한다")
    void CONNECT_Principal로_SEND를_허용한다() {
        // given
        given(jwtTokenProvider.retrieveAccessToken("test-token"))
                .willReturn(AccessTokenDto.of(100L, MemberRole.USER, "LOCAL", "test-token"));
        StompHeaderAccessor connect = StompHeaderAccessor.create(StompCommand.CONNECT);
        connect.setNativeHeader("Authorization", "Bearer test-token");
        connect.setLeaveMutable(true);
        Message<?> message = MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders());
        // when
        jwtChannelInterceptor.preSend(message, null);
        StompHeaderAccessor send = StompHeaderAccessor.create(StompCommand.SEND);
        send.setDestination("/app/location/update");
        send.setUser(connect.getUser());
        Message<?> request = MessageBuilder.createMessage(new byte[0], send.getMessageHeaders());
        // then
        assertThat(connect.getUser()).isNotNull();
        assertThat(jwtChannelInterceptor.preSend(request, null)).isSameAs(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/location/senior/42", "/topic/heart-rate/42"})
    @DisplayName("브로커에서 wildcard 구독과 위조 SEND를 차단하고 정상 가족에게 서버 메시지만 전달한다")
    void 브로커는_정상_가족에게_서버_메시지만_전달한다(String destination) {
        // given
        ExecutorSubscribableChannel inbound = new ExecutorSubscribableChannel();
        inbound.addInterceptor(jwtChannelInterceptor);
        ExecutorSubscribableChannel outbound = new ExecutorSubscribableChannel();
        ExecutorSubscribableChannel brokerChannel = new ExecutorSubscribableChannel();
        List<Message<?>> received = new ArrayList<>();
        outbound.subscribe(received::add);
        SimpleBrokerMessageHandler broker = new SimpleBrokerMessageHandler(
                inbound, outbound, brokerChannel, List.of("/topic", "/queue"));
        willThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .given(familyAccessService).verifyFamilyAccess(200L, 42L);
        broker.start();
        try {
            inbound.send(brokerFrame(StompCommand.CONNECT, null, "family", 100L));
            inbound.send(brokerFrame(StompCommand.CONNECT, null, "outsider", 200L));
            received.clear();

            // when
            assertThat(inbound.send(brokerFrame(StompCommand.SUBSCRIBE, "/topic/**", "outsider", 200L)))
                    .isFalse();
            assertThat(inbound.send(brokerFrame(StompCommand.SUBSCRIBE, destination, "outsider", 200L)))
                    .isFalse();
            assertThat(inbound.send(brokerFrame(StompCommand.SUBSCRIBE, destination, "family", 100L)))
                    .isTrue();
            assertThat(inbound.send(brokerFrame(StompCommand.SEND, destination, "outsider", 200L)))
                    .isFalse();
            assertThat(received).isEmpty();
            new SimpMessagingTemplate(brokerChannel).convertAndSend(destination, "server-measurement");

            // then
            assertThat(received).hasSize(1);
            assertThat(received.getFirst().getPayload()).isEqualTo("server-measurement");
            assertThat(SimpMessageHeaderAccessor.getSessionId(received.getFirst().getHeaders()))
                    .isEqualTo("family");
            assertThat(SimpMessageHeaderAccessor.getMessageType(received.getFirst().getHeaders()))
                    .isEqualTo(SimpMessageType.MESSAGE);
        } finally {
            broker.stop();
        }
    }

    private Message<?> brokerFrame(StompCommand command, String destination, String sessionId, Long memberId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId);
        accessor.setSessionAttributes(Map.of("memberId", memberId));
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (command == StompCommand.SUBSCRIBE) {
            accessor.setSubscriptionId("subscription-1");
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private ExecutorSubscribableChannel channel(List<Message<?>> received) {
        ExecutorSubscribableChannel channel = new ExecutorSubscribableChannel();
        channel.addInterceptor(jwtChannelInterceptor);
        channel.subscribe(received::add);
        return channel;
    }

    private Message<?> frame(StompCommand command, String destination, boolean authenticated) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        accessor.setSessionId("test-session");
        if (authenticated) {
            accessor.setSessionAttributes(Map.of("memberId", 100L));
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
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
