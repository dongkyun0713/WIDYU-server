package com.widyu.member.repository;

/** 긴급 수신자 스냅샷 조회 한 줄. 시니어 하나에 보호자 토큰 수만큼 나온다. */
public record EmergencyRecipientRow(
        Long seniorId, String seniorName, String seniorImage, Long guardianId, Long tokenId, String token) {
}
