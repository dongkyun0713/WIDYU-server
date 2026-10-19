package com.widyu.global.websocket;

import com.widyu.global.error.ErrorCode;
import com.widyu.global.log.ApiRequestContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.Principal;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 느린 STOMP 처리(심박 AI 호출, 센서 S3 업로드)를 수신 스레드에서 전용 실행기로 넘긴다(LLD-0091).
 * 수신 스레드에서 발신 세션·인증 정보·MDC를 확정해 넘기고, 작업 스레드는 처리 결과와 오류를 발신 세션에만 보낸다.
 * 실행기가 가득 차면 수신 스레드에서 대신 실행하지 않고 재시도 가능한 과부하 오류를 돌려준다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketWorkDispatcher {

    static final String ERRORS_DESTINATION = "/queue/errors";
    static final String REJECTED_METRIC = "websocket.work.rejected";

    /** 앱이 다른 오류와 구분해 재전송할 수 있게 오류 이름 자리에 업무 코드 이름을 싣는다. */
    static final Map<String, String> OVERLOADED = Map.of(
            "error", ErrorCode.TEMPORARILY_BUSY.name(),
            "message", ErrorCode.TEMPORARILY_BUSY.getMessage());

    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketExceptionHandler exceptionHandler;
    private final MeterRegistry meterRegistry;

    /**
     * 수신 스레드에서 부른다. {@code api}는 예외 로그의 Api 값(컨트롤러.메서드)으로, 기존 Grafana 규칙과 같은 이름을 쓴다.
     */
    public void dispatch(Executor executor, String api, SimpMessageHeaderAccessor accessor, Runnable work) {
        Principal user = accessor.getUser();
        String sessionId = accessor.getSessionId();
        String destination = accessor.getDestination();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        try {
            executor.execute(() -> run(work, api, destination, user, sessionId, mdc));
        } catch (RejectedExecutionException e) {
            meterRegistry.counter(REJECTED_METRIC, "api", api).increment();
            log.warn("WebSocket 처리 대기열이 가득 차 요청을 거절했습니다: api={}, memberId={}, sessionId={}",
                    api, nameOf(user), sessionId);
            sendToSession(nameOf(user), sessionId, ERRORS_DESTINATION, OVERLOADED);
        }
    }

    /**
     * 같은 회원의 다른 세션으로 퍼지지 않게 발신 세션에만 보낸다. {@code toMap()} 같은 일반 Map을 넘기면
     * SimpMessagingTemplate이 모든 키를 STOMP 네이티브 헤더로 옮겨 세션 ID가 사라진다(LLD-0088).
     */
    public void sendToSession(String user, String sessionId, String destination, Object payload) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);
        MessageHeaders headers = accessor.getMessageHeaders();
        messagingTemplate.convertAndSendToUser(user, destination, payload, headers);
    }

    private void run(Runnable work, String api, String destination, Principal user, String sessionId,
                     Map<String, String> mdc) {
        if (mdc != null) {
            MDC.setContextMap(mdc);
        }
        try {
            work.run();
        } catch (Exception e) {
            Map<String, String> error = exceptionHandler.handle(e, api, destination, ApiRequestContext.actorOf(user));
            sendToSession(nameOf(user), sessionId, ERRORS_DESTINATION, error);
        } finally {
            MDC.clear();
        }
    }

    /** {@code @SendToUser}와 같은 사용자 이름(Principal 이름 = 회원 ID)을 쓴다. 컨트롤러가 Principal 없는 요청을 먼저 거른다. */
    private static String nameOf(Principal user) {
        return user.getName();
    }
}
