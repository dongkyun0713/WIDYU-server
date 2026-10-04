package com.widyu.global.retry;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;

/**
 * 데드락·락 대기 초과로 실패한 트랜잭션을 새로 열어 재시도한다(LLD-0076).
 *
 * <p>재시도 정책: 최대 3회, 50ms에서 시작해 2배씩(최대 200ms) 증가하는 백오프.
 * 소진하면 마지막 예외가 그대로 전파된다. 연결 실패·제약 위반은 재시도하지 않는다.
 * DB가 중단된 상태에서 재시도하면 커넥션 대기 시간 × 횟수만큼 호출 스레드만 붙잡기 때문이다.
 *
 * <p><b>적용 조건</b>은 {@link RetryOnPointConflict}와 같다. 프록시를 거쳐 진입하는 최외곽
 * {@code @Transactional} 메서드에만 붙인다. 상위 트랜잭션 안에서 호출되면 실패한 트랜잭션을
 * 다시 쓰게 되어 재시도가 의미 없다. 재시도해도 결과가 같도록, 메서드 밖에서 받은 엔티티를
 * 그대로 저장하지 않는다(IDENTITY id가 실패한 시도에서 채워진 채 남는다).
 *
 * <p>ponytail: 락 대기 초과는 innodb_lock_wait_timeout(기본 50초)을 다 기다린 뒤 나므로 최악이면
 * 호출 스레드가 그 3배를 묶인다. 이 경로에서 락을 오래 기다리는 경우가 실제로 보이면 데드락(1213)만 재시도하도록 좁힌다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Retryable(
        retryFor = PessimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, multiplier = 2, maxDelay = 200)
)
public @interface RetryOnTransientLockFailure {
}
