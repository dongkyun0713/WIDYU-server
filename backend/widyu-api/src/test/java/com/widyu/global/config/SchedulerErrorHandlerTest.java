package com.widyu.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

class SchedulerErrorHandlerTest {

    @Test
    @DisplayName("스케줄러 예외를 처리하면 작업 이름과 예외를 한 줄로 남기고 예외를 다시 던지지 않는다")
    void 스케줄러_예외를_처리하면_작업_이름과_예외를_한_줄로_남기고_예외를_다시_던지지_않는다() {
        // given
        IllegalStateException exception = new IllegalStateException("결제 조회\n실패");
        exception.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.widyu.pay.application.PaymentService", "confirm", "PaymentService.java", 42),
                new StackTraceElement("com.widyu.pay.application.PaymentRecoveryScheduler$$SpringCGLIB$$0",
                        "recover", null, -1),
                new StackTraceElement("org.springframework.scheduling.support.ScheduledMethodRunnable", "run", null, 1)
        });
        Logger logger = (Logger) LoggerFactory.getLogger(SchedulerErrorHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when / then
            assertThatCode(() -> new SchedulerErrorHandler().handleError(exception)).doesNotThrowAnyException();
            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.get(0);
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .startsWith("[SCHEDULER] Task=PaymentRecoveryScheduler.recover "
                            + "ExceptionType=IllegalStateException Message=결제 조회\\n실패 StackTrace="
                            + "com.widyu.pay.application.PaymentService.confirm(PaymentService.java:42)\\n")
                    .doesNotContain("\n");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("WebSocket 브로커가 있어도 스케줄 작업은 알림용 에러 핸들러가 붙은 scheduling 스레드에서 돈다")
    void WebSocket_브로커가_있어도_스케줄_작업은_알림용_에러_핸들러가_붙은_scheduling_스레드에서_돈다() {
        new WebApplicationContextRunner()
                .withUserConfiguration(BrokerConfig.class, SchedulerConfig.class, FailingJob.class)
                .run(context -> {
                    // when
                    FailingJob job = context.getBean(FailingJob.class);
                    boolean ran = job.ran.await(5, TimeUnit.SECONDS);

                    // then
                    assertThat(ran).isTrue();
                    assertThat(job.threadName).startsWith("scheduling-");
                    assertThat(context.getBean(FailingJob.class).runs.get()).isGreaterThanOrEqualTo(1);
                });
    }

    @Configuration
    @EnableWebSocketMessageBroker
    static class BrokerConfig implements WebSocketMessageBrokerConfigurer {
        @Override
        public void configureMessageBroker(final MessageBrokerRegistry registry) {
            registry.enableSimpleBroker("/topic");
        }

        @Override
        public void registerStompEndpoints(final StompEndpointRegistry registry) {
            registry.addEndpoint("/ws");
        }
    }

    static class FailingJob {
        final CountDownLatch ran = new CountDownLatch(2);
        final AtomicInteger runs = new AtomicInteger();
        volatile String threadName;

        // 예외를 던져도 에러 핸들러가 삼켜 다음 실행이 이어져야 latch가 2번 내려간다.
        @Scheduled(fixedDelay = 10)
        void run() {
            threadName = Thread.currentThread().getName();
            runs.incrementAndGet();
            ran.countDown();
            throw new IllegalStateException("의도한 실패");
        }
    }

    @Test
    @DisplayName("앱 프레임이 없으면 작업 이름을 unknown으로 남긴다")
    void 앱_프레임이_없으면_작업_이름을_unknown으로_남긴다() {
        // given
        RuntimeException exception = new RuntimeException();
        exception.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("java.lang.Thread", "run", null, 1)
        });

        // when
        String line = SchedulerErrorHandler.toLogString(exception);

        // then
        assertThat(line).isEqualTo(
                "[SCHEDULER] Task=unknown ExceptionType=RuntimeException Message= StackTrace=java.lang.Thread.run(Unknown Source)");
    }
}
