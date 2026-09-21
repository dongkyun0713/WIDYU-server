package com.widyu.incident;

/** 본인이 답한 값. 무응답은 단말이 보내지 않고 서버가 마감으로 판정한다(ADR-0035 결정 5). */
public enum IncidentResponseValue {
    OK,
    HELP
}
