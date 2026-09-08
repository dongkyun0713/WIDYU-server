package com.widyu.global.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class MetricsConfig {

    private static final String WEBSOCKET_INBOUND_EXECUTOR_NAME = "websocket.inbound";

    @Bean
    public TimedAspect timedAspect(MeterRegistry meterRegistry) {
        return new TimedAspect(meterRegistry);
    }

    @Bean
    public ThreadPoolTaskExecutor websocketInboundExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int corePoolSize = Runtime.getRuntime().availableProcessors() * 2;
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(Integer.MAX_VALUE);
        executor.setQueueCapacity(Integer.MAX_VALUE);
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("clientInboundChannel-");
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
