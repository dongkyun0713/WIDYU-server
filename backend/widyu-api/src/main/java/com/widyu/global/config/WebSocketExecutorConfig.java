package com.widyu.global.config;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 느린 STOMP 처리와 응답 전송을 수신 실행기에서 떼어 낸 전용 실행기(LLD-0091).
 * 심박·센서 실행기는 스레드 수를 고정(core = max)한다. ThreadPoolExecutor는 대기열이 다 찬 뒤에야 스레드를 늘려,
 * 늘어날 때는 이미 대기열 전체가 밀려 있기 때문이다(#782 1단계 측정). 가득 차면 거절하고(AbortPolicy)
 * 호출자는 과부하 오류를 돌려준다. 수신 스레드에서 대신 실행하지 않는다.
 * 기본값의 근거는 application.yml 주석과 LLD-0091 검증 메모에 있다.
 */
@Configuration
public class WebSocketExecutorConfig {

    /** AI 읽기 타임아웃(10초) + 여유. 종료 시 진행 중인 AI 호출이 끝나 저장·ACK까지 갈 시간이다. */
    private static final int HEART_AWAIT_TERMINATION_SECONDS = 15;
    /** S3 apiCallTimeout(5초) + 여유. */
    private static final int SENSOR_AWAIT_TERMINATION_SECONDS = 10;
    private static final int OUTBOUND_AWAIT_TERMINATION_SECONDS = 5;

    @Bean
    public ThreadPoolTaskExecutor websocketHeartExecutor(
            @Value("${websocket.executor.heart.threads:8}") int threads,
            @Value("${websocket.executor.heart.queue-capacity:200}") int queueCapacity
    ) {
        return fixedExecutor("websocketHeart-", threads, queueCapacity, HEART_AWAIT_TERMINATION_SECONDS,
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    public ThreadPoolTaskExecutor websocketSensorExecutor(
            @Value("${websocket.executor.sensor.threads:8}") int threads,
            @Value("${websocket.executor.sensor.queue-capacity:500}") int queueCapacity
    ) {
        return fixedExecutor("websocketSensor-", threads, queueCapacity, SENSOR_AWAIT_TERMINATION_SECONDS,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 응답 전송 전용. 버리면 ACK가 사라지므로 가득 차면 보내려는 스레드가 직접 보낸다(CallerRuns).
     * 전송은 세션 버퍼에 넣는 짧은 작업이라 수신 스레드가 이 경로를 타도 AI 호출처럼 오래 묶이지 않는다.
     */
    @Bean
    public ThreadPoolTaskExecutor websocketOutboundExecutor(
            @Value("${websocket.executor.outbound.threads:4}") int threads,
            @Value("${websocket.executor.outbound.queue-capacity:1000}") int queueCapacity
    ) {
        // Spring이 응답 채널에 등록한 실행기의 스레드 이름을 이 값으로 바꾸므로 처음부터 같게 둔다.
        return fixedExecutor("clientOutboundChannel-", threads, queueCapacity, OUTBOUND_AWAIT_TERMINATION_SECONDS,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean
    public MeterBinder websocketWorkExecutorMetrics(
            @Qualifier("websocketHeartExecutor") ThreadPoolTaskExecutor heart,
            @Qualifier("websocketSensorExecutor") ThreadPoolTaskExecutor sensor,
            @Qualifier("websocketOutboundExecutor") ThreadPoolTaskExecutor outbound
    ) {
        return registry -> {
            ExecutorServiceMetrics.monitor(registry, heart.getThreadPoolExecutor(), "websocket.heart");
            ExecutorServiceMetrics.monitor(registry, sensor.getThreadPoolExecutor(), "websocket.sensor");
            ExecutorServiceMetrics.monitor(registry, outbound.getThreadPoolExecutor(), "websocket.outbound");
        };
    }

    private static ThreadPoolTaskExecutor fixedExecutor(String threadNamePrefix, int threads, int queueCapacity,
                                                        int awaitTerminationSeconds,
                                                        RejectedExecutionHandler rejectedExecutionHandler) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(rejectedExecutionHandler);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        return executor;
    }
}
