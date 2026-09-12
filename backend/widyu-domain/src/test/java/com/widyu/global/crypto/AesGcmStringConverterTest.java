package com.widyu.global.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AES-GCM 문자열 컨버터 단위 테스트")
class AesGcmStringConverterTest {

    // 테스트 전용 32바이트 키(Base64)
    private static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final AesGcmStringConverter converter = new AesGcmStringConverter(TEST_KEY);

    @Test
    @DisplayName("암호화 후 복호화하면 원문을 반환한다")
    void 암호화_후_복호화하면_원문을_반환한다() {
        // given
        String plain = "refresh-token-abc-123";

        // when
        String encrypted = converter.convertToDatabaseColumn(plain);

        // then
        assertThat(encrypted).startsWith("enc:v1:");
        assertThat(encrypted).isNotEqualTo(plain);
        assertThat(converter.convertToEntityAttribute(encrypted)).isEqualTo(plain);
    }

    @Test
    @DisplayName("같은 값도 매번 다른 암호문을 생성한다")
    void 같은_값도_매번_다른_암호문을_생성한다() {
        // when
        String first = converter.convertToDatabaseColumn("same-value");
        String second = converter.convertToDatabaseColumn("same-value");

        // then
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("접두사가 없는 기존 평문은 그대로 반환한다")
    void 접두사가_없는_기존_평문은_그대로_반환한다() {
        // when & then
        assertThat(converter.convertToEntityAttribute("legacy-plaintext")).isEqualTo("legacy-plaintext");
    }

    @Test
    @DisplayName("null은 null로 처리한다")
    void null은_null로_처리한다() {
        // when & then
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    @DisplayName("32바이트가 아닌 키는 예외가 발생한다")
    void 키가_32바이트가_아니면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> new AesGcmStringConverter("c2hvcnQ="))
                .isInstanceOf(IllegalStateException.class);
    }
}
