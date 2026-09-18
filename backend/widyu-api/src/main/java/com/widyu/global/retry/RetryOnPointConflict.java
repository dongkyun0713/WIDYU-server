package com.widyu.global.retry;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;

/**
 * 시니어 포인트 잔액(@Version) 낙관적 락 충돌 시 트랜잭션을 새로 열어 재시도한다.
 *
 * <p>재시도 정책: 최대 5회, 50ms에서 시작해 2배씩(최대 200ms) 증가하는 백오프.
 * 재시도가 모두 실패하면 {@link ObjectOptimisticLockingFailureException}가 그대로 전파되어
 * GlobalExceptionHandler가 409(POINT_CONCURRENT_UPDATE)로 응답한다.
 *
 * <p><b>적용 조건 — 반드시 최외곽 트랜잭션에 적용한다.</b>
 * 낙관적 락 충돌은 트랜잭션 커밋(flush) 시점에 발생한다. 이 애노테이션이 붙은 메서드가
 * 이미 열려 있는 상위 {@code @Transactional} 안에서 호출되면, 충돌은 상위 트랜잭션 커밋
 * 시점(= 이 메서드 프록시 바깥)에 터지므로 재시도가 동작하지 않는다. 따라서:
 * <ul>
 *   <li>프록시를 거쳐(다른 빈에서) 진입하는 {@code @Transactional} public 메서드이면서,</li>
 *   <li>호출 시점에 활성 트랜잭션이 없어 자신이 최외곽 트랜잭션 경계가 되는 진입점</li>
 * </ul>
 * 에만 적용한다. (예: {@code SeniorProfileService}의 포인트 증감 메서드가 스케줄러·컨트롤러
 * 에서 직접 호출될 때, {@code WalkService.updateSteps}, {@code AdminPointGrantService.grant})
 *
 * <p>결제 경로는 포인트를 증감하지 않는다(ADR-0029). 외부 부수효과가 있는 경로
 * (예: {@code AlbumUnlockService.unlockAlbum}의 동기 FCM 이벤트)는 재시도 없이 409로 응답한다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Retryable(
        retryFor = ObjectOptimisticLockingFailureException.class,
        maxAttempts = 5,
        backoff = @Backoff(delay = 50, multiplier = 2, maxDelay = 200)
)
public @interface RetryOnPointConflict {
}
