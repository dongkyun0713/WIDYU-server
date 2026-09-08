package com.widyu.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

class MetricsConfigTest {

    @Test
    @DisplayName("WebSocket inbound executor 메트릭을 등록하면 active와 queued gauge를 노출한다")
    void 웹소켓_inbound_executor_메트릭을_등록한다() {
        // given
        MetricsConfig config = new MetricsConfig();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolTaskExecutor executor = config.websocketInboundExecutor();
        executor.initialize();

        // when
        config.websocketInboundExecutorMetrics(executor).bindTo(registry);

        // then
        assertThat(registry.find("executor.active").tag("name", "websocket.inbound").gauge()).isNotNull();
        assertThat(registry.find("executor.queued").tag("name", "websocket.inbound").gauge()).isNotNull();

        executor.shutdown();
    }

    @Test
    @DisplayName("WebSocket inbound executor는 유한 큐와 호출자 실행 거부 정책을 사용한다")
    void 웹소켓_inbound_executor는_유한_큐와_호출자_실행_거부_정책을_사용한다() {
        // given
        MetricsConfig config = new MetricsConfig();
        ThreadPoolTaskExecutor executor = config.websocketInboundExecutor();
        executor.initialize();

        // when
        ThreadPoolExecutor threadPoolExecutor = executor.getThreadPoolExecutor();

        // then
        assertThat(threadPoolExecutor.getMaximumPoolSize()).isLessThan(Integer.MAX_VALUE);
        assertThat(threadPoolExecutor.getQueue().remainingCapacity()).isGreaterThan(0);
        assertThat(threadPoolExecutor.getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);

        executor.shutdown();
    }
}
