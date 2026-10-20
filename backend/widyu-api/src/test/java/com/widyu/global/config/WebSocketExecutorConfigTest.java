package com.widyu.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class WebSocketExecutorConfigTest {

    @Test
    @DisplayName("심박 실행기는 스레드 수가 고정이고 가득 차면 호출자에서 실행하지 않고 거절한다")
    void 심박_실행기는_스레드_수가_고정이고_가득_차면_거절한다() {
        // given
        ThreadPoolTaskExecutor executor = new WebSocketExecutorConfig().websocketHeartExecutor(8, 50);
        executor.initialize();

        // when
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();

        // then
        assertThat(pool.getCorePoolSize()).isEqualTo(8);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(8);
        assertThat(pool.getQueue().remainingCapacity()).isEqualTo(50);
        assertThat(pool.getRejectedExecutionHandler()).isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);

        executor.shutdown();
    }

    @Test
    @DisplayName("종료하면 진행 중인 심박 작업이 끝날 때까지 기다린다")
    void 종료하면_진행_중인_심박_작업이_끝날_때까지_기다린다() throws Exception {
        // given
        ThreadPoolTaskExecutor executor = new WebSocketExecutorConfig().websocketHeartExecutor(1, 1);
        executor.initialize();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        executor.execute(() -> {
            started.countDown();
            sleep(300);
            finished.set(true);
        });
        started.await(5, TimeUnit.SECONDS);

        // when
        executor.shutdown();

        // then
        assertThat(finished).isTrue();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
