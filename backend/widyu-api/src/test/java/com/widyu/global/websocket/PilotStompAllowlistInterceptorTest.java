package com.widyu.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

@DisplayName("실증 STOMP 허용 목록 인터셉터 단위 테스트")
@ExtendWith(MockitoExtension.class)
class PilotStompAllowlistInterceptorTest {

    private final PilotStompAllowlistInterceptor interceptor = new PilotStompAllowlistInterceptor();

    private Message<byte[]> stompMessage(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("허용한 목적지로의 SEND는 통과시킨다")
    void 허용한_목적지로의_SEND는_통과시킨다() {
        // given
        Message<byte[]> message = stompMessage(StompCommand.SEND, "/app/heart-rate/send-single");

        // when
        Message<?> result = interceptor.preSend(message, null);

        // then
        assertThat(result).isSameAs(message);
    }

    @Test
    @DisplayName("허용하지 않은 목적지로의 SEND는 차단한다")
    void 허용하지_않은_목적지로의_SEND는_차단한다() {
        // given
        Message<byte[]> message = stompMessage(StompCommand.SEND, "/app/albums/upload");

        // when
        Message<?> result = interceptor.preSend(message, null);

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("허용한 목적지로의 SUBSCRIBE는 통과시킨다")
    void 허용한_목적지로의_SUBSCRIBE는_통과시킨다() {
        // given
        Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE, "/topic/heart-rate/1");

        // when
        Message<?> result = interceptor.preSend(message, null);

        // then
        assertThat(result).isSameAs(message);
    }

    @Test
    @DisplayName("허용하지 않은 목적지로의 SUBSCRIBE는 차단한다")
    void 허용하지_않은_목적지로의_SUBSCRIBE는_차단한다() {
        // given
        Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE, "/topic/albums/1");

        // when
        Message<?> result = interceptor.preSend(message, null);

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("와일드카드 구독은 차단한다")
    void 와일드카드_구독은_차단한다() {
        // given
        Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE, "/topic/heart-rate/*");

        // when
        Message<?> result = interceptor.preSend(message, null);

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Long 범위를 벗어난 대상 ID 구독은 차단한다")
    void Long_범위를_벗어난_대상_ID_구독은_차단한다() {
        // given
        Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE, "/topic/heart-rate/99999999999999999999");

        // when
        Message<?> result = interceptor.preSend(message, null);

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("CONNECT 프레임은 목적지 검사 없이 통과시킨다")
    void CONNECT_프레임은_목적지_검사_없이_통과시킨다() {
        // given
        Message<byte[]> message = stompMessage(StompCommand.CONNECT, null);

        // when
        Message<?> result = interceptor.preSend(message, null);

        // then
        assertThat(result).isSameAs(message);
    }
}
