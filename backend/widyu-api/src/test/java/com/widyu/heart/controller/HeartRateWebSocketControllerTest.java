package com.widyu.heart.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.widyu.global.security.PrincipalDetails;
import com.widyu.heart.HeartRateResult;
import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.application.HeartRateService;
import com.widyu.heart.dto.request.HeartRateSingleRequest;
import com.widyu.heart.dto.response.HeartRateStatusResponse;
import com.widyu.member.MemberRole;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("HeartRateWebSocketController 단위 테스트")
class HeartRateWebSocketControllerTest {

    @Mock private HeartRateService heartRateService;
    @Mock private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private HeartRateWebSocketController heartRateWebSocketController;

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
        given(heartRateService.processHeartRate(memberId, request)).willReturn(response);
        ArgumentCaptor<Map<String, Object>> headersCaptor = ArgumentCaptor.captor();

        // when
        heartRateWebSocketController.sendHeartRate(request, principal, headerAccessor);

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

    private String[] messageMapping(Method method) {
        MessageMapping annotation = method.getAnnotation(MessageMapping.class);
        if (annotation == null) {
            return new String[0];
        }
        return annotation.value();
    }
}
