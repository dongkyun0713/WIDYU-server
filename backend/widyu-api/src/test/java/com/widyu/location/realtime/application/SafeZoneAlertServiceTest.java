package com.widyu.location.realtime.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.widyu.fcm.event.safezone.dto.SafeZoneEnterEvent;
import com.widyu.fcm.event.safezone.dto.SafeZoneExitEvent;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class SafeZoneAlertServiceTest {
    private static final String KEY = "safezone:alert:1";

    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> values;
    @Mock private ApplicationEventPublisher events;
    @InjectMocks private SafeZoneAlertService service;

    @Test
    @DisplayName("첫 안심구역 이탈이 30분 키를 예약하면 Z01 이벤트를 한 번 발행한다")
    void 첫_안심구역_이탈이_키를_예약하면_Z01_이벤트를_발행한다() {
        // given
        given(redisTemplate.opsForValue()).willReturn(values);
        given(values.setIfAbsent(KEY, "EXITED", 1800L, TimeUnit.SECONDS)).willReturn(true);

        // when
        service.handleSafeZoneTransition(1L, "HOME", null);

        // then
        then(events).should().publishEvent(new SafeZoneExitEvent(1L));
    }

    @Test
    @DisplayName("30분 키가 있는 동안 재이탈하면 Z01을 더 발행하지 않는다")
    void 삼십분_키가_있는_동안_재이탈하면_Z01을_더_발행하지_않는다() {
        // given
        given(redisTemplate.opsForValue()).willReturn(values);
        given(values.setIfAbsent(KEY, "EXITED", 1800L, TimeUnit.SECONDS)).willReturn(false);

        // when
        service.handleSafeZoneTransition(1L, "HOME", null);

        // then
        then(events).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("동시에 두 번 이탈하면 Redis 예약에 성공한 전이만 Z01 이벤트를 발행한다")
    void 동시에_두_번_이탈하면_예약한_전이만_Z01을_발행한다() throws Exception {
        // given
        AtomicBoolean reserved = new AtomicBoolean();
        given(redisTemplate.opsForValue()).willReturn(values);
        given(values.setIfAbsent(KEY, "EXITED", 1800L, TimeUnit.SECONDS))
                .willAnswer(invocation -> reserved.compareAndSet(false, true));
        CountDownLatch start = new CountDownLatch(1);

        // when
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> first = pool.submit(() -> {
                start.await();
                service.handleSafeZoneTransition(1L, "HOME", null);
                return null;
            });
            Future<?> second = pool.submit(() -> {
                start.await();
                service.handleSafeZoneTransition(1L, "HOME", null);
                return null;
            });
            start.countDown();
            first.get();
            second.get();
        }

        // then
        assertThat(reserved.get()).isTrue();
        then(events).should(times(1)).publishEvent(new SafeZoneExitEvent(1L));
    }

    @Test
    @DisplayName("첫 재진입이 EXITED를 RETURNED로 바꾸면 Z02 이벤트를 한 번 발행한다")
    void 첫_재진입이_상태를_바꾸면_Z02_이벤트를_발행한다() {
        // given
        given(redisTemplate.execute(any(RedisScript.class), eq(List.of(KEY)), eq("EXITED"), eq("RETURNED")))
                .willReturn(1L, 0L);

        // when
        service.handleSafeZoneTransition(1L, null, "HOME");
        service.handleSafeZoneTransition(1L, null, "HOME");

        // then
        then(events).should().publishEvent(new SafeZoneEnterEvent(1L));
        then(events).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("이탈 알림 창이 없는데 안심구역에 들어오면 돌아옴 소식을 만들지 않는다")
    void 이탈_알림_창이_없는데_들어오면_Z02를_만들지_않는다() {
        // given
        given(redisTemplate.execute(any(RedisScript.class), eq(List.of(KEY)), eq("EXITED"), eq("RETURNED")))
                .willReturn(0L);

        // when
        service.handleSafeZoneTransition(1L, null, "HOME");

        // then
        then(events).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("위치가 계속 같은 쪽이면 Redis 상태와 이벤트를 바꾸지 않는다")
    void 위치가_계속_같은_쪽이면_상태와_이벤트를_바꾸지_않는다() {
        // when
        service.handleSafeZoneTransition(1L, "HOME", "HOME");
        service.handleSafeZoneTransition(1L, null, null);

        // then
        then(redisTemplate).shouldHaveNoInteractions();
        then(events).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("이탈 위치 갱신이 롤백되면 30분 키를 조건부로 해제한다")
    void 이탈_위치_갱신이_롤백되면_키를_해제한다() {
        // given
        given(redisTemplate.opsForValue()).willReturn(values);
        given(values.setIfAbsent(KEY, "EXITED", 1800L, TimeUnit.SECONDS)).willReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            // when
            service.handleSafeZoneTransition(1L, "HOME", null);
            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }

            // then
            then(redisTemplate).should().execute(any(RedisScript.class), eq(List.of(KEY)), eq("EXITED"));
            then(events).should(times(1)).publishEvent(new SafeZoneExitEvent(1L));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("재진입 위치 갱신이 롤백되면 RETURNED를 EXITED로 되돌린다")
    void 재진입_위치_갱신이_롤백되면_상태를_되돌린다() {
        // given
        given(redisTemplate.execute(any(RedisScript.class), eq(List.of(KEY)), eq("EXITED"), eq("RETURNED")))
                .willReturn(1L);
        TransactionSynchronizationManager.initSynchronization();
        try {
            // when
            service.handleSafeZoneTransition(1L, null, "HOME");
            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }

            // then
            then(redisTemplate).should().execute(any(RedisScript.class), eq(List.of(KEY)),
                    eq("RETURNED"), eq("EXITED"));
            then(events).should().publishEvent(new SafeZoneEnterEvent(1L));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
