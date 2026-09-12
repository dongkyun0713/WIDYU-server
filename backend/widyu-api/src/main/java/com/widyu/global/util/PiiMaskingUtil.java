package com.widyu.global.util;

/**
 * 로그·외부 노출용 개인정보 마스킹 유틸. 원본 전화번호·이메일을 로그에 남기지 않도록
 * 공통으로 사용한다. 실명·좌표·토큰 등은 마스킹 대신 로그에서 제거하고 대상 ID만 남긴다.
 */
public final class PiiMaskingUtil {

    private PiiMaskingUtil() {
    }

    /**
     * 전화번호를 마스킹한다. 예: 01012345678 -> 010****5678
     */
    public static String maskPhoneNumber(final String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() < 8) {
            return "***";
        }

        if (phoneNumber.length() >= 11) {
            return phoneNumber.substring(0, 3) + "****" + phoneNumber.substring(7);
        }

        return phoneNumber.substring(0, 3) + "****";
    }

    /**
     * 이메일을 마스킹한다. 예: abcde@widyu.com -> a***@widyu.com
     */
    public static String maskEmail(final String email) {
        if (email == null) {
            return "***";
        }

        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return "***";
        }

        String domain = email.substring(atIndex);
        if (atIndex == 1) {
            return "*" + domain;
        }

        return email.charAt(0) + "***" + domain;
    }
}
