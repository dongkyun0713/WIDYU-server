package com.widyu.incident;

/** 사건 종류. SAFE_ZONE_EXIT는 기존 행 조회 호환용이며 새 사건에는 사용하지 않는다. */
public enum IncidentKind {
    HR_ANOMALY,
    FALL_SUSPECTED,
    SAFE_ZONE_EXIT
}
