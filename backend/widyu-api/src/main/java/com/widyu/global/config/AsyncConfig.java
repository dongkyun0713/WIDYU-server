package com.widyu.global.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * {@code @Async} 실행기에 상한을 둔다(LLD-0092).
 *
 * <p>Boot는 {@code Executor} 빈이 하나도 없을 때만 기본 풀을 만드는데, WebSocket 실행기 빈 때문에 만들지 않는다.
 * 지정하지 않으면 {@code @Async}가 작업마다 새 스레드를 만드는 {@code SimpleAsyncTaskExecutor}로 돈다.
 * <ul>
 *   <li>기본 실행기: 실행기를 지정하지 않은 {@code @Async}(위치 이벤트 등). 가득 차면 버리고 WARN·카운터를 남긴다.
 *       호출 스레드에서 대신 실행하지 않는다.</li>
 *   <li>영상 실행기: FFmpeg 처리 전용. 가득 차면 {@code TaskRejectedException}을 호출자에게 던져 호출자가 보상·재시도 안내를 한다.</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    public static final String ALBUM_VIDEO_EXECUTOR = "albumVideoExecutor";
    static final String DEFAULT_EXECUTOR = "defaultAsyncExecutor";
    static final String REJECTED_METRIC = "async.executor.rejected";

    private static final String DEFAULT_METRIC_NAME = "async.default";
    private static final String VIDEO_METRIC_NAME = "async.album-video";

    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final int defaultPoolSize;
    private final int defaultQueueCapacity;
    private final int defaultAwaitSeconds;
    private final int videoPoolSize;
    private final int videoQueueCapacity;
    private final int videoAwaitSeconds;

    public AsyncConfig(
            ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${async.default.pool-size}") int defaultPoolSize,
            @Value("${async.default.queue-capacity}") int defaultQueueCapacity,
            @Value("${async.default.await-termination-seconds}") int defaultAwaitSeconds,
            @Value("${async.album-video.pool-size}") int videoPoolSize,
            @Value("${async.album-video.queue-capacity}") int videoQueueCapacity,
            @Value("${async.album-video.await-termination-seconds}") int videoAwaitSeconds
    ) {
        this.meterRegistry = meterRegistry;
        this.defaultPoolSize = defaultPoolSize;
        this.defaultQueueCapacity = defaultQueueCapacity;
        this.defaultAwaitSeconds = defaultAwaitSeconds;
        this.videoPoolSize = videoPoolSize;
        this.videoQueueCapacity = videoQueueCapacity;
        this.videoAwaitSeconds = videoAwaitSeconds;
    }

    @Bean(DEFAULT_EXECUTOR)
    public ThreadPoolTaskExecutor defaultAsyncExecutor() {
        return boundedExecutor("async-", defaultPoolSize, defaultQueueCapacity, defaultAwaitSeconds,
                discardWithWarn(DEFAULT_METRIC_NAME));
    }

    @Bean(ALBUM_VIDEO_EXECUTOR)
    public ThreadPoolTaskExecutor albumVideoExecutor() {
        return boundedExecutor("album-video-", videoPoolSize, videoQueueCapacity, videoAwaitSeconds,
                abort(VIDEO_METRIC_NAME));
    }

    @Override
    public Executor getAsyncExecutor() {
        return defaultAsyncExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        // 예외 메시지에 개인정보·좌표가 들어갈 수 있어 메서드와 예외 종류만 남긴다
        return (ex, method, params) -> log.error("비동기 작업 실패: method={}.{}, errorType={}",
                method.getDeclaringClass().getSimpleName(), method.getName(), ex.getClass().getName());
    }

    @Bean
    public MeterBinder asyncExecutorMetrics(
            @Qualifier(DEFAULT_EXECUTOR) ThreadPoolTaskExecutor defaultExecutor,
            @Qualifier(ALBUM_VIDEO_EXECUTOR) ThreadPoolTaskExecutor videoExecutor
    ) {
        return registry -> {
            ExecutorServiceMetrics.monitor(registry, defaultExecutor.getThreadPoolExecutor(), DEFAULT_METRIC_NAME);
            ExecutorServiceMetrics.monitor(registry, videoExecutor.getThreadPoolExecutor(), VIDEO_METRIC_NAME);
        };
    }

    private ThreadPoolTaskExecutor boundedExecutor(String threadNamePrefix, int poolSize, int queueCapacity,
                                                   int awaitSeconds, RejectedExecutionHandler rejectionHandler) {
        ThreadPoolTaskExecutor executor = new BoundedShutdownExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(rejectionHandler);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitSeconds);
        return executor;
    }

    private RejectedExecutionHandler discardWithWarn(String executorName) {
        return (task, executor) -> {
            countRejection(executorName);
            log.warn("비동기 작업 대기열이 가득 차 작업을 버립니다: executor={}, queueSize={}",
                    executorName, executor.getQueue().size());
        };
    }

    private RejectedExecutionHandler abort(String executorName) {
        return (task, executor) -> {
            countRejection(executorName);
            throw new RejectedExecutionException("비동기 실행기 포화: " + executorName);
        };
    }

    private void countRejection(String executorName) {
        meterRegistry.ifAvailable(registry -> registry.counter(REJECTED_METRIC, "name", executorName).increment());
    }

    /**
     * 종료 대기에 실제 상한을 둔다. Spring은 {@code awaitTerminationSeconds}가 지나도 경고만 남기고 실행 중 작업을
     * 끊지 않아, 긴 영상 처리와 대기열이 남으면 비데몬 실행기 스레드가 JVM 종료를 막는다. 대기 시간이 지나면
     * 실행 중 작업을 중단하고 대기열 작업을 버린 뒤 그 수를 남긴다(LLD-0092 6절).
     */
    static class BoundedShutdownExecutor extends ThreadPoolTaskExecutor {

        @Override
        public void shutdown() {
            super.shutdown();
            ThreadPoolExecutor executor = getThreadPoolExecutor();
            if (executor.isTerminated()) {
                return;
            }
            List<Runnable> dropped = executor.shutdownNow();
            log.warn("비동기 실행기 종료 대기 시간이 지나 남은 작업을 중단합니다: threadNamePrefix={}, droppedQueued={}",
                    getThreadNamePrefix(), dropped.size());
        }
    }
}
