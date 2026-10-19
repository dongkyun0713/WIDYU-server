package com.widyu.heart.application;

import com.widyu.heart.repository.HeartRateEventRepository;
import java.time.LocalDateTime;
import java.util.Locale;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 심박 저장의 무결성 오류가 동시 재전송의 UK 충돌인지 가린다. 배치·단건 경로가 함께 쓴다(LLD-0089).
 *
 * <p>같은 트랜잭션에서 판정·위급 행이 다른 제약에 걸려도 같은 시각을 다른 요청이 먼저 저장했다면 행은 있다.
 * 그래서 행 재확인만으로는 부족하고, 위반한 제약이 심박 UK인지 먼저 본다. 제약 이름은 Hibernate가 DB 메시지에서
 * 뽑은 값이라 DB마다 모양이 다르다(MySQL은 {@code heart_rate_event.} 접두, H2는 대문자에 인덱스 접미).
 * 그래서 대소문자를 무시하고 포함 여부로 본다.
 */
public final class HeartEventConflict {

    static final String MEMBER_TIME_UK = "uk_heart_event_member_time";

    private HeartEventConflict() {
    }

    /**
     * 심박 UK 충돌이고 그 행이 실제로 있으면 true다. 재확인 조회까지 실패하면 원래 무결성 예외를 던진다.
     * 조용히 삼키지 않는다.
     */
    static boolean storedByConcurrentRequest(
            DataIntegrityViolationException cause, HeartRateEventRepository repository,
            Long memberId, LocalDateTime measuredAt) {
        if (!violatesMemberTimeUk(cause)) {
            return false;
        }
        try {
            return repository.existsByMemberIdAndMeasuredAt(memberId, measuredAt);
        } catch (RuntimeException recheckFailure) {
            cause.addSuppressed(recheckFailure);
            throw cause;
        }
    }

    public static boolean violatesMemberTimeUk(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ConstraintViolationException violation
                    && violation.getConstraintName() != null
                    && violation.getConstraintName().toLowerCase(Locale.ROOT).contains(MEMBER_TIME_UK)) {
                return true;
            }
        }
        return false;
    }
}
