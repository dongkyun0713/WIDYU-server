package com.widyu.incident.dto.request;

/** 개발 서버 전용 심박 위급 강제 발생 요청. 보호자가 호출할 때만 대상 시니어를 지정한다. */
public record DevHeartEmergencyRequest(Long seniorId) {
}
