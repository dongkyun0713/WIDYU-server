package com.widyu.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.willThrow;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.application.FamilyAccessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

@ExtendWith(MockitoExtension.class)
@DisplayName("FamilyTopicOutboundInterceptor 현재 권한 재검증 단위 테스트")
class FamilyTopicOutboundInterceptorTest {

    @Mock private FamilyAccessService familyAccessService;

    @Test
    @DisplayName("정지된 보호자의 보호 토픽 수신을 차단한다")
    void 정지된_보호자의_보호_토픽_수신을_차단한다() {
        // given
        WebSocketSessionRegistry sessionRegistry = new WebSocketSessionRegistry();
        sessionRegistry.register("session-1", 100L);
        FamilyTopicOutboundInterceptor interceptor = new FamilyTopicOutboundInterceptor(
                familyAccessService, sessionRegistry);
        Message<String> message = protectedTopicMessage("/topic/heart-rate/42", "session-1");
        willThrow(new BusinessException(ErrorCode.FORBIDDEN, "활성 회원만 실시간 정보를 받을 수 있습니다."))
                .given(familyAccessService).verifyActiveFamilyAccess(100L, 42L);

        // when
        Message<?> result = interceptor.preSend(message, null);

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("등록되지 않은 세션의 보호 토픽 수신을 차단한다")
    void 등록되지_않은_세션의_보호_토픽_수신을_차단한다() {
        // given
        FamilyTopicOutboundInterceptor interceptor = new FamilyTopicOutboundInterceptor(
                familyAccessService, new WebSocketSessionRegistry());
        Message<String> message = protectedTopicMessage("/topic/location/senior/42", "unknown-session");

        // when
        Message<?> result = interceptor.preSend(message, null);

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("사용자 큐 메시지는 현재 가족 재검증 없이 유지한다")
    void 사용자_큐_메시지는_현재_가족_재검증_없이_유지한다() {
        // given
        FamilyTopicOutboundInterceptor interceptor = new FamilyTopicOutboundInterceptor(
                familyAccessService, new WebSocketSessionRegistry());
        Message<String> message = protectedTopicMessage("/user/queue/heart-rate/result", "session-1");

        // when
        Message<?> result = interceptor.preSend(message, null);

        // then
        assertThat(result).isSameAs(message);
    }

    private Message<String> protectedTopicMessage(String destination, String sessionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        accessor.setDestination(destination);
        accessor.setSessionId(sessionId);
        return MessageBuilder.createMessage("measurement", accessor.getMessageHeaders());
    }
}
