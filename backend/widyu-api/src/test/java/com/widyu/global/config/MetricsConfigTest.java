package com.widyu.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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
}
