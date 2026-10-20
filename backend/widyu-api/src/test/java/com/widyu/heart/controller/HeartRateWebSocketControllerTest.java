package com.widyu.heart.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.widyu.global.security.PrincipalDetails;
import com.widyu.global.websocket.WebSocketExceptionHandler;
import com.widyu.global.websocket.WebSocketWorkDispatcher;
import com.widyu.heart.HeartRateResult;
import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.application.HeartRateService;
import com.widyu.heart.dto.request.HeartRateSingleRequest;
import com.widyu.heart.dto.response.HeartRateStatusResponse;
import com.widyu.member.MemberRole;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
@DisplayName("HeartRateWebSocketController 단위 테스트")
class HeartRateWebSocketControllerTest {

    @Mock private HeartRateService heartRateService;
    @Mock private SimpMessagingTemplate messagingTemplate;

    @Test
    @DisplayName("단건 처리 결과는 인증 회원의 현재 WebSocket 세션에 ACK로 전달한다")
    void 단건_처리_결과를_인증회원의_현재세션에_ACK로_전달한다() {
        // given
        Long memberId = 42L;
        String sessionId = "heart-session-1";
        LocalDateTime measuredAt = LocalDateTime.of(2026, 9, 8, 10, 0);
        HeartRateSingleRequest request = HeartRateSingleRequest.of(78, measuredAt, "서울시", "UNKNOWN");
        HeartRateResult result = HeartRateResult.of(memberId, HeartRateStatus.NORMAL, 78, measuredAt);
        HeartRateStatusResponse response = HeartRateStatusResponse.from(result);
        PrincipalDetails principal = new PrincipalDetails(memberId, MemberRole.USER);
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headerAccessor.setSessionId(sessionId);
        headerAccessor.setUser(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        given(heartRateService.processHeartRate(memberId, request)).willReturn(response);
        ArgumentCaptor<Map<String, Object>> headersCaptor = ArgumentCaptor.captor();

        // when
        controller(messagingTemplate, new SyncTaskExecutor()).sendHeartRate(request, headerAccessor);

        // then
        then(messagingTemplate).should().convertAndSend(
                "/topic/heart-rate/42",
                response
        );
        then(messagingTemplate).should().convertAndSendToUser(
                eq("42"),
                eq("/queue/heart-rate/result"),
                same(response),
                headersCaptor.capture()
        );
        assertThat(headersCaptor.getValue())
                .containsEntry(SimpMessageHeaderAccessor.SESSION_ID_HEADER, sessionId);
    }

    @Test
    @DisplayName("단건 ACK 메시지는 발신 세션 ID를 STOMP 네이티브 헤더가 아닌 세션 헤더로 싣는다")
    void 단건_ACK_메시지는_발신_세션_ID를_세션_헤더로_싣는다() {
        // given
        Long memberId = 42L;
        String sessionId = "heart-session-1";
        LocalDateTime measuredAt = LocalDateTime.of(2026, 9, 8, 10, 0);
        HeartRateSingleRequest request = HeartRateSingleRequest.of(78, measuredAt, null, null);
        PrincipalDetails principal = new PrincipalDetails(memberId, MemberRole.USER);
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headerAccessor.setSessionId(sessionId);
        headerAccessor.setUser(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        given(heartRateService.processHeartRate(memberId, request))
                .willReturn(HeartRateStatusResponse.of(memberId, HeartRateStatus.NORMAL, 78, measuredAt));
        List<Message<?>> sent = new ArrayList<>();
        ExecutorSubscribableChannel brokerChannel = new ExecutorSubscribableChannel();
        brokerChannel.subscribe(sent::add);
        HeartRateWebSocketController controller =
                controller(new SimpMessagingTemplate(brokerChannel), new SyncTaskExecutor());

        // when
        controller.sendHeartRate(request, headerAccessor);

        // then
        Message<?> ack = sent.stream()
                .filter(message -> "/user/42/queue/heart-rate/result"
                        .equals(SimpMessageHeaderAccessor.getDestination(message.getHeaders())))
                .findFirst()
                .orElseThrow();
        assertThat(SimpMessageHeaderAccessor.getSessionId(ack.getHeaders())).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("Principal 없이 심박을 전송하면 처리하지 않고 예외가 발생한다")
    void Principal_없이_심박을_전송하면_예외가_발생한다() {
        // given
        HeartRateSingleRequest request = HeartRateSingleRequest.of(78, LocalDateTime.of(2026, 9, 8, 10, 0), null, null);
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headerAccessor.setSessionId("heart-session-1");
        headerAccessor.setSessionAttributes(Map.of("memberId", 42L));

        // when / then
        assertThatThrownBy(() -> controller(messagingTemplate, new SyncTaskExecutor()).sendHeartRate(request, headerAccessor))
                .isInstanceOf(IllegalStateException.class);
        then(heartRateService).shouldHaveNoInteractions();
        then(messagingTemplate).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("심박을 받으면 처리를 심박 실행기에 넘기고 바로 반환하며 ACK는 넘긴 작업이 끝난 뒤 보낸다")
    void 심박을_받으면_처리를_심박_실행기에_넘기고_ACK는_작업이_끝난_뒤_보낸다() {
        // given
        Long memberId = 42L;
        LocalDateTime measuredAt = LocalDateTime.of(2026, 9, 8, 10, 0);
        HeartRateSingleRequest request = HeartRateSingleRequest.of(78, measuredAt, null, null);
        PrincipalDetails principal = new PrincipalDetails(memberId, MemberRole.USER);
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headerAccessor.setSessionId("heart-session-1");
        headerAccessor.setUser(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        given(heartRateService.processHeartRate(memberId, request))
                .willReturn(HeartRateStatusResponse.of(memberId, HeartRateStatus.NORMAL, 78, measuredAt));
        List<Runnable> handedOff = new ArrayList<>();
        HeartRateWebSocketController controller = controller(messagingTemplate, handedOff::add);

        // when
        controller.sendHeartRate(request, headerAccessor);

        // then
        assertThat(handedOff).hasSize(1);
        then(heartRateService).shouldHaveNoInteractions();
        then(messagingTemplate).shouldHaveNoInteractions();

        handedOff.get(0).run();
        then(messagingTemplate).should().convertAndSendToUser(
                eq("42"), eq("/queue/heart-rate/result"), any(), anyMap());
    }

    @Test
    @DisplayName("심박 WebSocket에는 단건 메시지 매핑만 존재한다")
    void 심박_WebSocket에는_단건_메시지매핑만_존재한다() {
        // when
        String[] mappings = Arrays.stream(HeartRateWebSocketController.class.getDeclaredMethods())
                .map(this::messageMapping)
                .flatMap(Arrays::stream)
                .toArray(String[]::new);

        // then
        assertThat(mappings)
                .containsExactly("/heart-rate/send-single");
    }

    private HeartRateWebSocketController controller(SimpMessagingTemplate template, TaskExecutor heartExecutor) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WebSocketWorkDispatcher dispatcher =
                new WebSocketWorkDispatcher(template, new WebSocketExceptionHandler(), registry);
        return new HeartRateWebSocketController(heartRateService, template, dispatcher, heartExecutor, registry);
    }

    private String[] messageMapping(Method method) {
        MessageMapping annotation = method.getAnnotation(MessageMapping.class);
        if (annotation == null) {
            return new String[0];
        }
        return annotation.value();
    }
}
