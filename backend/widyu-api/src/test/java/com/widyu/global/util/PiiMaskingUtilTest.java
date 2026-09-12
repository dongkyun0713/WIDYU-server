package com.widyu.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("개인정보 마스킹 유틸 단위 테스트")
class PiiMaskingUtilTest {

    @Test
    @DisplayName("11자리 전화번호는 가운데를 마스킹한다")
    void 열한자리_전화번호는_가운데를_마스킹한다() {
        // when
        String masked = PiiMaskingUtil.maskPhoneNumber("01012345678");

        // then
        assertThat(masked).isEqualTo("010****5678");
    }

    @Test
    @DisplayName("null이거나 너무 짧은 전화번호는 완전 마스킹한다")
    void null이거나_너무_짧은_전화번호는_완전_마스킹한다() {
        // when & then
        assertThat(PiiMaskingUtil.maskPhoneNumber(null)).isEqualTo("***");
        assertThat(PiiMaskingUtil.maskPhoneNumber("0101")).isEqualTo("***");
    }

    @Test
    @DisplayName("이메일은 첫 글자만 남기고 로컬 파트를 마스킹한다")
    void 이메일은_첫_글자만_남기고_로컬_파트를_마스킹한다() {
        // when
        String masked = PiiMaskingUtil.maskEmail("hongildong@widyu.com");

        // then
        assertThat(masked).isEqualTo("h***@widyu.com");
    }

    @Test
    @DisplayName("null이거나 형식이 아닌 이메일은 완전 마스킹한다")
    void null이거나_형식이_아닌_이메일은_완전_마스킹한다() {
        // when & then
        assertThat(PiiMaskingUtil.maskEmail(null)).isEqualTo("***");
        assertThat(PiiMaskingUtil.maskEmail("noatsign")).isEqualTo("***");
        assertThat(PiiMaskingUtil.maskEmail("@widyu.com")).isEqualTo("***");
    }
}
