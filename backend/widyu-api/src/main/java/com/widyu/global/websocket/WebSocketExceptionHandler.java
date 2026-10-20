package com.widyu.global.websocket;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.log.ApiRequestContext;
import com.widyu.global.log.StackTraceLog;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.HandlerMethod;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.web.bind.annotation.ControllerAdvice;

/**
 * STOMP @MessageMapping 처리 중 난 예외를 발신자에게 돌려주고 로그를 남긴다(LLD-0084).
 * 클라이언트 잘못(업무 규칙 4xx·입력 검증·형식 오류)은 WARN, 서버 오류는 알림이 읽는 [WS_EXCEPTION] 한 줄 ERROR로 남긴다.
 */
@Slf4j
@ControllerAdvice
public class WebSocketExceptionHandler {

    private static final String NONE = "-";

    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public Map<String, String> handleException(
            final Exception exception,
            final HandlerMethod handlerMethod,
            final SimpMessageHeaderAccessor accessor
    ) {
        return handle(exception, handlerName(handlerMethod), accessor.getDestination(),
                ApiRequestContext.actorOf(accessor.getUser()));
    }

    /**
     * 예외를 분류해 로그를 남기고 /user/queue/errors 응답 본문을 만든다. 전용 실행기에서 난 예외는
     * {@code @MessageExceptionHandler}가 잡지 못해 {@link WebSocketWorkDispatcher}가 이 메서드를 직접 부른다(LLD-0091).
     */
    public Map<String, String> handle(
            final Exception exception,
            final String api,
            final String destination,
            final String actor
    ) {
        String resolvedDestination = destination;
        if (resolvedDestination == null) {
            resolvedDestination = NONE;
        }
        if (isClientError(exception)) {
            log.warn("WebSocket 요청 거부: destination={}, actor={}, exceptionType={}",
                    resolvedDestination, actor, exception.getClass().getSimpleName());
            return Map.of("error", exception.getClass().getSimpleName(), "message", messageOf(exception));
        }
        log.error(toLogString(exception, api, resolvedDestination, actor));
        // 서버 오류의 내부 메시지는 클라이언트에 돌려주지 않는다(REST 500과 같은 기준).
        return Map.of("error", exception.getClass().getSimpleName(), "message", ErrorCode.INTERNAL_SERVER_ERROR.getMessage());
    }

    static String toLogString(
            final Exception exception,
            final String api,
            final String destination,
            final String actor
    ) {
        return String.format(
                "[WS_EXCEPTION] Api=%s Destination=%s Actor=%s ExceptionType=%s Message=%s StackTrace=%s",
                api,
                destination,
                actor,
                exception.getClass().getName(),
                StackTraceLog.escapeLineBreaks(messageOf(exception)),
                StackTraceLog.oneLine(exception)
        );
    }

    private static boolean isClientError(final Exception exception) {
        if (exception instanceof BusinessException businessException) {
            return businessException.getErrorCode().getHttpStatus().is4xxClientError();
        }
        return exception instanceof MethodArgumentNotValidException || exception instanceof MessageConversionException;
    }

    private static String handlerName(final HandlerMethod handlerMethod) {
        if (handlerMethod == null) {
            return "unknown";
        }
        return handlerMethod.getBeanType().getSimpleName() + "." + handlerMethod.getMethod().getName();
    }

    private static String messageOf(final Exception exception) {
        if (exception.getMessage() == null) {
            return "";
        }
        return exception.getMessage();
    }
}
