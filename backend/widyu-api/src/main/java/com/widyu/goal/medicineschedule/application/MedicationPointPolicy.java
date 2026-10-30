package com.widyu.goal.medicineschedule.application;

/**
 * 의약품 복용 포인트 계산 규칙.
 * 인증 즉시 적립(MedicationProofTransactionService)이 쓰는 금액 기준을 여기 한 곳에 둔다.
 * 인증 1회에 10p, 그날 유효한 일정을 모두 채운 인증에 완주 보너스 20p를 더한다.
 * 완주 보너스는 복용 날짜별로 한 번만 준다(LLD-0038 §11).
 */
public final class MedicationPointPolicy {

    /** 인증 1회분 적립액. 이보다 큰 복약 인증 적립 이력은 완주 보너스를 포함한 건이다. */
    public static final long POINTS_PER_MEDICATION = 10L;
    private static final long BONUS_POINTS_FOR_COMPLETION = 20L;

    private MedicationPointPolicy() {
    }

    /**
     * 이번 인증으로 그날 유효한 일정을 모두 채웠는지.
     * proofCountAfter는 이번 인증을 저장한 뒤의 그날 인증 횟수다.
     */
    public static boolean completesDay(long proofCountAfter, long totalSchedules) {
        return totalSchedules > 0 && proofCountAfter == totalSchedules;
    }

    /** 이번 인증 한 건의 적립액. 완주 보너스를 줄 때만 보너스를 더한다. */
    public static long calculateEarnedPoints(boolean grantsCompletionBonus) {
        if (grantsCompletionBonus) {
            return POINTS_PER_MEDICATION + BONUS_POINTS_FOR_COMPLETION;
        }
        return POINTS_PER_MEDICATION;
    }
}
