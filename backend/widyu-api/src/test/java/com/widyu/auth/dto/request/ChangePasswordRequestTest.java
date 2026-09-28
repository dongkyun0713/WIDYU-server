package com.widyu.auth.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ChangePasswordRequest 검증")
class ChangePasswordRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1", "abc123!", "abcdefgh1", "abcdefgh!", "12345678!", "abcdefgh1234!"})
    @DisplayName("규칙에 맞지 않는 비밀번호로 재설정하면 검증 오류가 발생한다")
    void 규칙에_맞지_않는_비밀번호는_거부한다(String password) {
        // given
        ChangePasswordRequest request = new ChangePasswordRequest(password);

        // when & then
        assertThat(validator.validate(request)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcd123!", "Abcdef12345!"})
    @DisplayName("영문·숫자·특수기호를 포함한 8~12자 비밀번호로 재설정하면 검증을 통과한다")
    void 규칙에_맞는_비밀번호는_통과한다(String password) {
        // given
        ChangePasswordRequest request = new ChangePasswordRequest(password);

        // when & then
        assertThat(validator.validate(request)).isEmpty();
    }
}
