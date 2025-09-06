package com.widyu.global.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PhoneNumberUtil {

    /**
     * 전화번호를 표준화된 형태로 정규화합니다.
     * - 공백, 하이픈, 괄호 등 숫자와 '+' 이외의 문자 제거
     * - +82로 시작하면 국내형(0으로 치환)으로 변환
     * - 그 외는 국제번호 표기 유지(+ 포함)
     * 
     * 예시:
     * "+82 10-1234-5678" -> "01012345678"
     * "+82-10-1234-5678" -> "01012345678"  
     * "+1-202-555-0123"  -> "+12025550123"
     * "010-1234-5678"    -> "01012345678"
     * 
     * @param phoneNumber 원본 전화번호
     * @return 정규화된 전화번호, 입력이 null이거나 빈 문자열이면 null 반환
     */
    public static String normalize(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return null;
        }

        String cleaned = phoneNumber.replaceAll("[^0-9+]", "");
        if (cleaned.isBlank()) {
            return null;
        }

        if (cleaned.indexOf('+') > 0) {
            cleaned = cleaned.charAt(0) == '+'
                    ? "+" + cleaned.substring(1).replace("+", "")
                    : cleaned.replace("+", "");
        }

        if (cleaned.startsWith("+82")) {
            return "0" + cleaned.substring(3);
        }

        return cleaned;
    }
}