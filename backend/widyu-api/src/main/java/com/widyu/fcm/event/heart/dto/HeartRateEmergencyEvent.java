package com.widyu.fcm.event.heart.dto;

/**
 * 심박 위급을 보호자에게 알리라는 신호.
 *
 * <p>{@code decisionId}는 이 위급을 낳은 판정 기록을 가리킨다. 전송이 성공하면 그 행에 알림 도달 사실을
 * 채운다(LLD-0053 5.2). 판정 기록을 남기지 않는 단건 경로에서는 null이다.
 */
public record HeartRateEmergencyEvent(Long memberId, String decisionId) {
}
