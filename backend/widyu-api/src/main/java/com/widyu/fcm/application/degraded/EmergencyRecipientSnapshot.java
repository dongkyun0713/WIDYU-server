package com.widyu.fcm.application.degraded;

import java.util.List;

/** DB 없이 긴급 알림을 보내려고 Redis에 미리 둔 시니어별 수신자 목록. 토큰이 들어 있어 로그에 남기지 않는다. */
public record EmergencyRecipientSnapshot(String seniorName, String image, List<Recipient> recipients) {

    public record Recipient(Long guardianId, Long tokenId, String token) {
    }
}
