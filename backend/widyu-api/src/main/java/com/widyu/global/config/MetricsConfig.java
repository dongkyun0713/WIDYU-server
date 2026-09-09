package com.widyu.global.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class MetricsConfig {

    private static final String WEBSOCKET_INBOUND_EXECUTOR_NAME = "websocket.inbound";
    private static final int WEBSOCKET_INBOUND_MAX_POOL_SIZE = 16;
    private static final int WEBSOCKET_INBOUND_QUEUE_CAPACITY = 1_000;

    @Bean
    public TimedAspect timedAspect(MeterRegistry meterRegistry) {
        return new TimedAspect(meterRegistry);
    }

    @Bean
    public ThreadPoolTaskExecutor websocketInboundExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int corePoolSize = Math.min(Runtime.getRuntime().availableProcessors() * 2, WEBSOCKET_INBOUND_MAX_POOL_SIZE);
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(WEBSOCKET_INBOUND_MAX_POOL_SIZE);
        executor.setQueueCapacity(WEBSOCKET_INBOUND_QUEUE_CAPACITY);
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("clientInboundChannel-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }

    @Bean
    public MeterBinder websocketInboundExecutorMetrics(
            @Qualifier("websocketInboundExecutor") ThreadPoolTaskExecutor websocketInboundExecutor
    ) {
        return registry -> ExecutorServiceMetrics.monitor(
                registry,
                websocketInboundExecutor.getThreadPoolExecutor(),
                WEBSOCKET_INBOUND_EXECUTOR_NAME
        );
    }
}
