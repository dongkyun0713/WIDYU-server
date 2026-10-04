package com.widyu.global.retry;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.stereotype.Component;

/**
 * {@link RetryOnTransientLockFailure}의 실패한 시도와 소진을 센다(LLD-0076 5절 5).
 * 마지막 시도의 실패도 {@code attempt_failed}로 세므로, 소진된 호출은 두 태그에 모두 잡힌다.
 *
 * <p>리스너를 지정하지 않은 {@code @Retryable}에는 모든 리스너 빈이 붙으므로, 락 충돌 예외만 센다.
 * 그래서 {@link RetryOnPointConflict} 메서드가 락 충돌로 끝나도 1회 시도 후 {@code exhausted}로 잡힌다.
 * 그 메서드는 락 충돌을 재시도하지 않으므로 「락 충돌로 끝난 호출」이라는 의미는 같다.
 * 로그에는 메서드·시도 횟수·예외 타입만 남긴다. 인자(심박 값 등)는 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransientLockRetryListener implements RetryListener {

    private static final String METRIC = "db.transient.retry";

    private final MeterRegistry meterRegistry;

    @Override
    public <T, E extends Throwable> void onError(
            RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
        if (!(throwable instanceof PessimisticLockingFailureException)) {
            return;
        }
        String method = methodOf(context);
        meterRegistry.counter(METRIC, "outcome", "attempt_failed", "method", method).increment();
        log.warn("DB 락 충돌로 시도 실패: method={}, attempt={}, errorType={}",
                method, context.getRetryCount(), throwable.getClass().getSimpleName());
    }

    /** 락 충돌 예외를 안고 끝났다면 시도를 모두 쓴 것이다. 그 밖의 예외는 처음부터 재시도 대상이 아니다. */
    @Override
    public <T, E extends Throwable> void close(
            RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
        if (!(throwable instanceof PessimisticLockingFailureException)) {
            return;
        }
        String method = methodOf(context);
        meterRegistry.counter(METRIC, "outcome", "exhausted", "method", method).increment();
        log.warn("DB 락 충돌 재시도 소진: method={}, attempts={}, errorType={}",
                method, context.getRetryCount(), throwable.getClass().getSimpleName());
    }

    /** {@code RetryContext.NAME}은 메서드 시그니처 전체다. 메트릭 태그로 쓰려고 {@code 클래스.메서드}만 남긴다. */
    private String methodOf(RetryContext context) {
        Object name = context.getAttribute(RetryContext.NAME);
        if (name == null) {
            return "unknown";
        }
        String signature = name.toString();
        int paren = signature.indexOf('(');
        if (paren >= 0) {
            signature = signature.substring(0, paren);
        }
        int methodDot = signature.lastIndexOf('.');
        int classDot = signature.lastIndexOf('.', methodDot - 1);
        return signature.substring(classDot + 1);
    }
}
