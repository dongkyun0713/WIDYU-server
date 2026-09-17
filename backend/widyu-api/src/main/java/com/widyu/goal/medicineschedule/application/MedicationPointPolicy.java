package com.widyu.goal.medicineschedule.application;

/**
 * 의약품 복용 포인트 계산 규칙.
 * 인증 즉시 적립(MedicationProofTransactionService)이 쓰는 금액 기준을 여기 한 곳에 둔다.
 * 하루치 누적 금액과 이번 인증 한 건의 증분이 같은 식에서 나오도록 상수와 보너스 조건을 모은다.
 */
public final class MedicationPointPolicy {

    private static final long POINTS_PER_MEDICATION = 10L;
    private static final long BONUS_POINTS_FOR_COMPLETION = 20L;

    private MedicationPointPolicy() {
    }

    /** 하루치 누적 포인트: 인증 1회당 10p, 그날 유효한 일정을 모두 채우면 보너스 20p. */
    public static long calculateDailyPoints(long proofCount, long totalSchedules) {
        long points = proofCount * POINTS_PER_MEDICATION;
        if (proofCount == totalSchedules && totalSchedules > 0) {
            return points + BONUS_POINTS_FOR_COMPLETION;
        }
        return points;
    }

    /**
     * 이번 인증 한 건으로 늘어나는 포인트.
     * proofCountAfter는 이번 인증을 저장한 뒤의 그날 인증 횟수다.
     */
    public static long calculateEarnedPoints(long proofCountAfter, long totalSchedules) {
        return calculateDailyPoints(proofCountAfter, totalSchedules)
                - calculateDailyPoints(proofCountAfter - 1, totalSchedules);
    }
}
