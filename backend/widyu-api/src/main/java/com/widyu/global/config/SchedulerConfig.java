package com.widyu.global.config;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

// 테스트 컨텍스트에서 스케줄러가 목 의존성을 호출해 간헐 실패를 일으키지 않도록 프로퍼티로 끌 수 있게 한다
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "widyu.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerConfig implements SchedulingConfigurer, DisposableBean {

    /**
     * @Scheduled 전용 스케줄러(LLD-0084). 지정하지 않으면 @EnableWebSocketMessageBroker가 등록한
     * messageBrokerTaskScheduler(MessageBroker-N 스레드)에서 돌아 실패 로그를 알림이 구분하지 못한다.
     * 병렬도는 그 브로커 스케줄러와 같은 CPU 수로 유지한다. 빈으로 등록하지 않아 TaskScheduler 타입 주입이 모호해지지 않는다.
     */
    private final ThreadPoolTaskScheduler scheduler = createScheduler();

    static ThreadPoolTaskScheduler createScheduler() {
        final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Runtime.getRuntime().availableProcessors());
        scheduler.setThreadNamePrefix("scheduling-");
        scheduler.setErrorHandler(new SchedulerErrorHandler());
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureTasks(final ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(scheduler);
    }

    @Override
    public void destroy() {
        scheduler.shutdown();
    }
}
