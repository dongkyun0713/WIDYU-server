package com.widyu.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.PrincipalDetails;
import com.widyu.member.MemberRole;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.handler.HandlerMethod;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

class WebSocketExceptionHandlerTest {

    private static final String DESTINATION = "/app/heart-rate/send-single";

    @Test
    @DisplayName("서버 오류가 나면 기능과 목적지와 요청자를 담은 한 줄 ERROR를 남기고 내부 메시지는 돌려주지 않는다")
    void 서버_오류가_나면_기능과_목적지와_요청자를_담은_한_줄_ERROR를_남기고_내부_메시지는_돌려주지_않는다() throws Exception {
        List<ILoggingEvent> logs = captureLogs(() -> {
            // when
            Map<String, String> response = new WebSocketExceptionHandler().handleException(
                    new IllegalStateException("redis\ntimeout"), handlerMethod(), accessor());

            // then
            assertThat(response).containsEntry("message", ErrorCode.INTERNAL_SERVER_ERROR.getMessage());
        });

        assertThat(logs).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .startsWith("[WS_EXCEPTION] Api=SampleController.fail Destination=" + DESTINATION
                            + " Actor=USER#12 ExceptionType=java.lang.IllegalStateException Message=redis\\ntimeout StackTrace=")
                    .doesNotContain("\n");
        });
    }

    @Test
    @DisplayName("업무 규칙 4xx 예외는 WARN으로 남기고 업무 메시지를 돌려준다")
    void 업무_규칙_4xx_예외는_WARN으로_남기고_업무_메시지를_돌려준다() throws Exception {
        List<ILoggingEvent> logs = captureLogs(() -> {
            // when
            Map<String, String> response = new WebSocketExceptionHandler().handleException(
                    new BusinessException(ErrorCode.FORBIDDEN), handlerMethod(), accessor());

            // then
            assertThat(response).containsEntry("message", ErrorCode.FORBIDDEN.getMessage());
        });

        assertThat(logs).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).doesNotContain("[WS_EXCEPTION]");
        });
    }

    @Test
    @DisplayName("5xx로 분류된 업무 예외는 서버 오류로 ERROR를 남긴다")
    void _5xx로_분류된_업무_예외는_서버_오류로_ERROR를_남긴다() throws Exception {
        List<ILoggingEvent> logs = captureLogs(() -> new WebSocketExceptionHandler().handleException(
                new BusinessException(ErrorCode.FILE_UPLOAD_FAILED), handlerMethod(), accessor()));

        assertThat(logs).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).startsWith("[WS_EXCEPTION] ");
        });
    }

    @Test
    @DisplayName("메시지가 없는 예외도 응답을 만들 수 있다")
    void 메시지가_없는_예외도_응답을_만들_수_있다() throws Exception {
        captureLogs(() -> {
            // when
            Map<String, String> response = new WebSocketExceptionHandler().handleException(
                    new BusinessException(ErrorCode.FORBIDDEN) {
                        @Override
                        public String getMessage() {
                            return null;
                        }
                    }, handlerMethod(), accessor());

            // then
            assertThat(response).containsEntry("message", "");
        });
    }

    @Test
    @DisplayName("실제 STOMP 처리에서 예외가 나면 Spring이 핸들러와 헤더를 넘겨 한 줄 ERROR가 남는다")
    void 실제_STOMP_처리에서_예외가_나면_Spring이_핸들러와_헤더를_넘겨_한_줄_ERROR가_남는다() {
        new WebApplicationContextRunner()
                .withUserConfiguration(BrokerConfig.class, SampleController.class, WebSocketExceptionHandler.class)
                .run(context -> {
                    MessageChannel inbound = context.getBean("clientInboundChannel", MessageChannel.class);
                    SimpMessageHeaderAccessor headers = accessor();
                    headers.setSessionId("session-1");
                    headers.setSessionAttributes(new HashMap<>());
                    headers.setLeaveMutable(true);

                    Logger logger = (Logger) LoggerFactory.getLogger(WebSocketExceptionHandler.class);
                    ListAppender<ILoggingEvent> appender = new ListAppender<>();
                    appender.start();
                    logger.addAppender(appender);
                    try {
                        // when — 인바운드 채널은 별도 스레드에서 처리하므로 로그가 남을 때까지 기다린다.
                        inbound.send(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()));
                        long deadline = System.currentTimeMillis() + 5_000;
                        while (appender.list.isEmpty() && System.currentTimeMillis() < deadline) {
                            Thread.sleep(20);
                        }

                        // then
                        assertThat(List.copyOf(appender.list)).extracting(ILoggingEvent::getFormattedMessage)
                                .singleElement().asString()
                                .startsWith("[WS_EXCEPTION] Api=SampleController.fail Destination=" + DESTINATION
                                        + " Actor=USER#12 ExceptionType=java.lang.IllegalStateException");
                    } finally {
                        logger.detachAppender(appender);
                        appender.stop();
                    }
                });
    }

    private static List<ILoggingEvent> captureLogs(ThrowingRunnable action) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(WebSocketExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static HandlerMethod handlerMethod() throws NoSuchMethodException {
        return new HandlerMethod(new SampleController(), SampleController.class.getMethod("fail"));
    }

    private static SimpMessageHeaderAccessor accessor() {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination(DESTINATION);
        PrincipalDetails principal = new PrincipalDetails(12L, MemberRole.USER);
        accessor.setUser(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        return accessor;
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Controller
    static class SampleController {
        @MessageMapping("/heart-rate/send-single")
        public void fail() {
            throw new IllegalStateException("처리 실패");
        }
    }

    @Configuration
    @EnableWebSocketMessageBroker
    static class BrokerConfig implements WebSocketMessageBrokerConfigurer {
        @Override
        public void configureMessageBroker(final MessageBrokerRegistry registry) {
            registry.enableSimpleBroker("/topic", "/queue");
            registry.setApplicationDestinationPrefixes("/app");
        }

        @Override
        public void registerStompEndpoints(final StompEndpointRegistry registry) {
            registry.addEndpoint("/ws");
        }
    }
}
