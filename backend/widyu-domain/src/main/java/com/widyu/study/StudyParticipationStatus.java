package com.widyu.study;

/** 정책서 1.5.2 2층 보존 상태. ACTIVE(식별 원본) → PSEUDONYMIZED(가명 연구본) → DESTROYED(파기). */
public enum StudyParticipationStatus {
    ACTIVE,
    PSEUDONYMIZED,
    DESTROYED
}
