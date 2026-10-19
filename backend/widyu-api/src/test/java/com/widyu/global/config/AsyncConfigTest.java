package com.widyu.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("AsyncConfig 실행기 포화 처리 테스트")
class AsyncConfigTest {

    @Test
    @DisplayName("영상 실행기가 가득 차면 호출 스레드에서 실행하지 않고 거절 예외를 던지며 거절 수를 센다")
    void 영상_실행기가_가득_차면_호출_스레드에서_실행하지_않고_거절한다() throws Exception {
        // given
        MeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolTaskExecutor executor = tinyConfig(registry).albumVideoExecutor();
        executor.initialize();
        CountDownLatch release = new CountDownLatch(1);
        saturate(executor, release);
        AtomicReference<String> ranOn = new AtomicReference<>();

        // when & then
        try {
            assertThatThrownBy(() -> executor.execute(() -> ranOn.set(Thread.currentThread().getName())))
                    .isInstanceOf(TaskRejectedException.class);
            assertThat(ranOn.get()).isNull();
            assertThat(registry.counter(AsyncConfig.REJECTED_METRIC, "name", "async.album-video").count())
                    .isEqualTo(1.0);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("기본 실행기가 가득 차면 호출 스레드에서 실행하지 않고 작업을 버리며 WARN과 거절 수를 남긴다")
    void 기본_실행기가_가득_차면_작업을_버리고_WARN과_카운터를_남긴다(CapturedOutput output) throws Exception {
        // given
        MeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolTaskExecutor executor = tinyConfig(registry).defaultAsyncExecutor();
        executor.initialize();
        CountDownLatch release = new CountDownLatch(1);
        saturate(executor, release);
        AtomicReference<String> ranOn = new AtomicReference<>();

        // when
        try {
            executor.execute(() -> ranOn.set(Thread.currentThread().getName()));
        } finally {
            release.countDown();
            executor.shutdown();
        }

        // then
        assertThat(executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ranOn.get()).isNull();
        assertThat(registry.counter(AsyncConfig.REJECTED_METRIC, "name", "async.default").count()).isEqualTo(1.0);
        assertThat(output).contains("WARN").contains("executor=async.default");
    }

    @Test
    @DisplayName("종료 대기 시간이 지나도 끝나지 않은 작업이 있으면 실행 중 작업을 중단하고 대기열 작업을 버린다")
    void 종료_대기_시간이_지나면_남은_작업을_중단하고_버린다(CapturedOutput output) throws Exception {
        // given
        ThreadPoolTaskExecutor executor = tinyConfig(new SimpleMeterRegistry()).albumVideoExecutor();
        executor.initialize();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        AtomicReference<Boolean> queuedRan = new AtomicReference<>(false);
        executor.execute(() -> {
            running.countDown();
            try {
                neverReleased.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        executor.execute(() -> queuedRan.set(true));

        // when
        long startedAt = System.nanoTime();
        executor.shutdown();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        // then
        assertThat(executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(elapsedMillis).isLessThan(5_000);
        assertThat(interrupted.get()).isTrue();
        assertThat(queuedRan.get()).isFalse();
        assertThat(output).contains("droppedQueued=1");
    }

    /** 스레드 1개·대기열 1칸인 설정. */
    private AsyncConfig tinyConfig(MeterRegistry registry) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("meterRegistry", registry));
        return new AsyncConfig(beans.getBeanProvider(MeterRegistry.class), 1, 1, 1, 1, 1, 1);
    }

    /** 스레드 1개를 붙잡고 대기열 1칸을 채운다. */
    private void saturate(ThreadPoolTaskExecutor executor, CountDownLatch release) throws InterruptedException {
        CountDownLatch running = new CountDownLatch(1);
        executor.execute(() -> {
            running.countDown();
            awaitQuietly(release);
        });
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        executor.execute(() -> awaitQuietly(release));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
