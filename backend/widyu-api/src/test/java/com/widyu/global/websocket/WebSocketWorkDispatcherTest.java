package com.widyu.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.PrincipalDetails;
import com.widyu.member.MemberRole;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocketWorkDispatcher 단위 테스트")
class WebSocketWorkDispatcherTest {

    private static final String API = "HeartRateWebSocketController.sendHeartRate";
    private static final String DESTINATION = "/app/heart-rate/send-single";
    private static final String SESSION_ID = "heart-session-1";

    @Mock private SimpMessagingTemplate messagingTemplate;

    @Test
    @DisplayName("전용 실행기가 가득 차면 거절한 요청을 수신 스레드에서 실행하지 않고 그 수만큼 발신 세션에 과부하 오류를 보낸다")
    void 전용_실행기가_가득_차면_거절한_요청_수만큼_발신_세션에_과부하_오류를_보낸다() throws Exception {
        // given — 스레드 1개가 막혀 있고 대기열 1칸이 찬 실행기
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WebSocketWorkDispatcher dispatcher = dispatcher(registry);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger processed = new AtomicInteger();
        Runnable work = () -> {
            started.countDown();
            await(release);
            processed.incrementAndGet();
        };
        dispatcher.dispatch(executor, API, accessor(), work);
        started.await(5, TimeUnit.SECONDS);
        dispatcher.dispatch(executor, API, accessor(), work);
        ArgumentCaptor<Object> body = ArgumentCaptor.captor();
        ArgumentCaptor<Map<String, Object>> headers = ArgumentCaptor.captor();

        // when — 넘친 요청 3건
        int overflow = 3;
        for (int i = 0; i < overflow; i++) {
            dispatcher.dispatch(executor, API, accessor(), work);
        }
        release.countDown();
        executor.getThreadPoolExecutor().shutdown();
        executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS);

        // then
        then(messagingTemplate).should(times(overflow))
                .convertAndSendToUser(eq("12"), eq("/queue/errors"), body.capture(), headers.capture());
        assertThat(body.getAllValues()).containsOnly(Map.of(
                "error", "TEMPORARILY_BUSY", "message", ErrorCode.TEMPORARILY_BUSY.getMessage()));
        assertThat(headers.getAllValues())
                .allSatisfy(header -> assertThat(header).containsEntry(SimpMessageHeaderAccessor.SESSION_ID_HEADER, SESSION_ID));
        assertThat(processed.get()).isEqualTo(2);
        assertThat(registry.counter(WebSocketWorkDispatcher.REJECTED_METRIC, "api", API).count()).isEqualTo(overflow);
    }

    @Test
    @DisplayName("작업 중 서버 오류가 나면 발신 세션 오류 큐로 보내고 기존 형식의 한 줄 WS_EXCEPTION ERROR를 남긴다")
    void 작업_중_서버_오류가_나면_발신_세션_오류_큐로_보내고_WS_EXCEPTION_ERROR를_남긴다() {
        // given
        WebSocketWorkDispatcher dispatcher = dispatcher(new SimpleMeterRegistry());
        ArgumentCaptor<Object> body = ArgumentCaptor.captor();
        ArgumentCaptor<Map<String, Object>> headers = ArgumentCaptor.captor();

        // when
        List<ILoggingEvent> logs = captureLogs(() -> dispatcher.dispatch(new SyncTaskExecutor(), API, accessor(), () -> {
            throw new IllegalStateException("ai\ntimeout");
        }));

        // then
        then(messagingTemplate).should()
                .convertAndSendToUser(eq("12"), eq("/queue/errors"), body.capture(), headers.capture());
        assertThat(body.getValue()).isEqualTo(Map.of(
                "error", "IllegalStateException", "message", ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
        assertThat(headers.getValue()).containsEntry(SimpMessageHeaderAccessor.SESSION_ID_HEADER, SESSION_ID);
        assertThat(logs).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .startsWith("[WS_EXCEPTION] Api=" + API + " Destination=" + DESTINATION
                            + " Actor=USER#12 ExceptionType=java.lang.IllegalStateException Message=ai\\ntimeout StackTrace=")
                    .doesNotContain("\n");
        });
    }

    @Test
    @DisplayName("작업 중 업무 규칙 4xx 예외가 나면 WARN을 남기고 업무 메시지를 발신 세션 오류 큐로 보낸다")
    void 작업_중_업무_규칙_예외가_나면_WARN을_남기고_업무_메시지를_보낸다() {
        // given
        WebSocketWorkDispatcher dispatcher = dispatcher(new SimpleMeterRegistry());
        ArgumentCaptor<Object> body = ArgumentCaptor.captor();

        // when
        List<ILoggingEvent> logs = captureLogs(() -> dispatcher.dispatch(new SyncTaskExecutor(), API, accessor(), () -> {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }));

        // then
        then(messagingTemplate).should().convertAndSendToUser(eq("12"), eq("/queue/errors"), body.capture(), anyMap());
        assertThat(body.getValue()).isEqualTo(Map.of(
                "error", "BusinessException", "message", ErrorCode.FORBIDDEN.getMessage()));
        assertThat(logs).singleElement().satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
    }

    private WebSocketWorkDispatcher dispatcher(SimpleMeterRegistry registry) {
        return new WebSocketWorkDispatcher(messagingTemplate, new WebSocketExceptionHandler(), registry);
    }

    private static SimpMessageHeaderAccessor accessor() {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(SESSION_ID);
        accessor.setDestination(DESTINATION);
        PrincipalDetails principal = new PrincipalDetails(12L, MemberRole.USER);
        accessor.setUser(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        return accessor;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<ILoggingEvent> captureLogs(Runnable action) {
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
}
