package com.widyu.incident;

/** 사건의 운영 상태(형식서 §3.7, 계약 §2.5-5). */
public enum IncidentState {
    OPEN,
    CHECKING,
    OK_CLOSED,
    ESCALATED,
    RESOLVED
}
