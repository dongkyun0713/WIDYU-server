package com.widyu.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.MemberSessionService;
import com.widyu.member.application.FamilyAccessService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocket 세션 폐기와 기존 구독 전달 검증")
class WsSessionGuardTest {
    @Mock private MemberSessionService memberSessionService;
    @Mock private FamilyAccessService familyAccessService;
    @InjectMocks private WsSessionGuard guard;

    @ParameterizedTest
    @ValueSource(strings = {"/topic/location/senior/42", "/topic/heart-rate/42"})
    @DisplayName("가족 해제 후 기존 구독으로 서버가 발송해도 전달하지 않고 실제 세션을 닫는다")
    void 가족_해제_후_기존_브로커_구독에_전달하지_않는다(String destination) throws Exception {
        // given
        given(memberSessionService.isCurrent(1L, 0L)).willReturn(true);
        WebSocketSession socket = socket("family", 1L);
        guard.register(socket);
        var inbound = new ExecutorSubscribableChannel();
        inbound.addInterceptor(guard);
        var outbound = new ExecutorSubscribableChannel();
        outbound.addInterceptor(guard);
        var brokerChannel = new ExecutorSubscribableChannel();
        List<Message<?>> delivered = new ArrayList<>();
        outbound.subscribe(delivered::add);
        var broker = new SimpleBrokerMessageHandler(inbound, outbound, brokerChannel, List.of("/topic", "/queue"));
        broker.start();
        try {
            inbound.send(frame(StompCommand.CONNECT, null, "family"));
            inbound.send(frame(StompCommand.SUBSCRIBE, destination, "family"));
            delivered.clear();
            var sender = new SimpMessagingTemplate(brokerChannel);
            sender.convertAndSend(destination, "before-unlink");
            assertThat(delivered).hasSize(1);
            delivered.clear();

            // when
            willThrow(new BusinessException(ErrorCode.FORBIDDEN)).given(familyAccessService).verifyFamilyAccess(1L, 42L);
            sender.convertAndSend(destination, "after-unlink");

            // then
            assertThat(delivered).isEmpty();
            verify(socket).close(CloseStatus.POLICY_VIOLATION);
        } finally {
            broker.stop();
        }
    }

    @Test
    @DisplayName("executor 대기 중 폐기되면 실행 직전 outbound 메시지를 차단한다")
    void 대기중_메시지는_실행_직전에_재검증한다() throws Exception {
        // given
        given(memberSessionService.isCurrent(1L, 0L)).willReturn(true);
        WebSocketSession socket = socket("queued", 1L);
        guard.register(socket);
        Message<?> message = frame(StompCommand.MESSAGE, "/queue/location/ack-userqueued", "queued");
        assertThat(guard.preSend(message, null)).isSameAs(message);

        // when
        given(memberSessionService.isCurrent(1L, 0L)).willReturn(false);

        // then
        assertThat(guard.beforeHandle(message, null, ignored -> {})).isNull();
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("폐기 커밋 이벤트를 받으면 모든 기기 연결을 닫고 SEND를 차단한다")
    void 폐기_이벤트로_모든_연결을_종료한다() throws Exception {
        // given
        given(memberSessionService.isCurrent(1L, 0L)).willReturn(true);
        WebSocketSession first = socket("first", 1L);
        WebSocketSession second = socket("second", 1L);
        guard.register(first);
        guard.register(second);

        // when
        given(memberSessionService.isCurrent(1L, 0L)).willReturn(false);
        guard.revoked(new MemberSessionService.SessionsRevoked(1L));

        // then
        verify(first).close(CloseStatus.POLICY_VIOLATION);
        verify(second).close(CloseStatus.POLICY_VIOLATION);
        assertThat(guard.preSend(frame(StompCommand.SEND, "/app/location/update", "first"), null)).isNull();
    }

    @Test
    @DisplayName("다른 인스턴스의 폐기를 발견하면 유휴 연결도 닫는다")
    void 유휴_연결의_폐기를_확인한다() throws Exception {
        // given
        given(memberSessionService.isCurrent(1L, 0L)).willReturn(true);
        WebSocketSession socket = socket("idle", 1L);
        guard.register(socket);
        // when
        given(memberSessionService.isCurrent(1L, 0L)).willReturn(false);
        guard.closeRevokedSessions();
        // then
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("DB 검증 장애가 발생하면 연결을 닫고 프레임을 거절한다")
    void 검증_장애에서_전달을_허용하지_않는다() throws Exception {
        // given
        given(memberSessionService.isCurrent(1L, 0L)).willReturn(true);
        WebSocketSession socket = socket("failure", 1L);
        guard.register(socket);
        // when
        given(memberSessionService.isCurrent(1L, 0L)).willThrow(new IllegalStateException("database unavailable"));
        // then
        assertThat(guard.beforeHandle(frame(StompCommand.MESSAGE, "/topic/heart-rate/42", "failure"), null, ignored -> {})).isNull();
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }

    private WebSocketSession socket(String id, Long memberId) {
        WebSocketSession socket = mock(WebSocketSession.class);
        given(socket.getId()).willReturn(id);
        given(socket.getAttributes()).willReturn(Map.of("memberId", memberId, "authVersion", 0L));
        return socket;
    }

    @Test
    @DisplayName("정상 프레임은 실행 직전 한 번만 DB 버전과 가족 관계를 검증하고 전달한다")
    void 큐_진입과_실행의_DB_중복검사를_제거한다() {
        // given
        given(memberSessionService.isCurrent(1L, 0L)).willReturn(true);
        guard.register(socket("once", 1L));
        org.mockito.Mockito.clearInvocations(memberSessionService);
        var channel = new ExecutorSubscribableChannel();
        channel.addInterceptor(guard);
        List<Message<?>> delivered = new ArrayList<>();
        channel.subscribe(delivered::add);
        // when
        channel.send(frame(StompCommand.MESSAGE, "/topic/heart-rate/42", "once"));
        // then
        assertThat(delivered).hasSize(1);
        verify(memberSessionService).isCurrent(1L, 0L);
        verify(familyAccessService).verifyFamilyAccess(1L, 42L);
    }

    private Message<?> frame(StompCommand command, String destination, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (command == StompCommand.SUBSCRIBE) {
            accessor.setSubscriptionId("sub-1");
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
