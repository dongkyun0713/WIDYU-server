package com.widyu.auth.integration;

import com.widyu.global.util.PhoneNumberUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("전화번호 매칭 통합 테스트")
class PhoneNumberMatchingTest {

    @Test
    @DisplayName("카카오와 네이버 전화번호 정규화 후 동일 결과 확인")
    void testPhoneNumberNormalizationConsistency() {
        String kakaoPhone = "+82 10-1234-5678";
        String naverPhone = "+82-10-1234-5678";
        String naverPhone2 = "010-1234-5678";
        
        String normalizedKakao = PhoneNumberUtil.normalize(kakaoPhone);
        String normalizedNaver1 = PhoneNumberUtil.normalize(naverPhone);
        String normalizedNaver2 = PhoneNumberUtil.normalize(naverPhone2);
        
        assertEquals("01012345678", normalizedKakao);
        assertEquals("01012345678", normalizedNaver1);
        assertEquals("01012345678", normalizedNaver2);
        
        assertEquals(normalizedKakao, normalizedNaver1);
        assertEquals(normalizedKakao, normalizedNaver2);
    }
    
    @Test 
    @DisplayName("문제 시나리오: 카카오 가입 후 네이버 로그인 시 동일 전화번호 매칭")
    void testScenarioKakaoThenNaver() {
        String kakaoSignupPhone = "01012345678";
        String naverLoginPhone = "+82 10-1234-5678"; 
        
        String kakaoNormalized = PhoneNumberUtil.normalize(kakaoSignupPhone);
        String naverNormalized = PhoneNumberUtil.normalize(naverLoginPhone);
        
        assertEquals(kakaoNormalized, naverNormalized);
        assertEquals("01012345678", kakaoNormalized);
        assertEquals("01012345678", naverNormalized);
    }
}