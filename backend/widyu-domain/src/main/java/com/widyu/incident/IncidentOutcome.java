package com.widyu.incident;

/** 보호자의 사후 판정. 이 값이 실증의 지도학습 라벨이다(형식서 §3.7). */
public enum IncidentOutcome {
    TRUE_EMERGENCY,
    FALSE_ALARM,
    UNKNOWN
}
