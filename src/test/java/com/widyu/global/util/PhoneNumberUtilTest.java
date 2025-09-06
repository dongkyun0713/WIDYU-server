package com.widyu.global.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PhoneNumberUtilTest {

    @Test
    void normalize_shouldReturnNull_whenInputIsNull() {
        assertNull(PhoneNumberUtil.normalize(null));
    }

    @Test
    void normalize_shouldReturnNull_whenInputIsEmpty() {
        assertNull(PhoneNumberUtil.normalize(""));
        assertNull(PhoneNumberUtil.normalize("   "));
    }

    @Test
    void normalize_shouldNormalizeKoreanPhoneWithPlus82() {
        assertEquals("01012345678", PhoneNumberUtil.normalize("+82 10-1234-5678"));
        assertEquals("01012345678", PhoneNumberUtil.normalize("+8210-1234-5678"));
        assertEquals("01012345678", PhoneNumberUtil.normalize("+82-10-1234-5678"));
    }

    @Test
    void normalize_shouldNormalizeDomesticPhoneNumber() {
        assertEquals("01012345678", PhoneNumberUtil.normalize("010-1234-5678"));
        assertEquals("01012345678", PhoneNumberUtil.normalize("010 1234 5678"));
    }

    @Test
    void normalize_shouldKeepInternationalNumbers() {
        assertEquals("+12025550123", PhoneNumberUtil.normalize("+1-202-555-0123"));
        assertEquals("+12025550123", PhoneNumberUtil.normalize("+1 202 555 0123"));
    }

    @Test
    void normalize_shouldRemoveNonNumericCharactersExceptPlus() {
        assertEquals("01012345678", PhoneNumberUtil.normalize("010-(1234)-5678"));
        assertEquals("01012345678", PhoneNumberUtil.normalize("010.1234.5678"));
    }

    @Test
    void normalize_shouldReturnNull_whenNoDigits() {
        assertNull(PhoneNumberUtil.normalize("---"));
        assertNull(PhoneNumberUtil.normalize("abc"));
    }
}