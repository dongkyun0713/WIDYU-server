package com.widyu.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

class DataIntegrityViolationKindTest {

    @Test
    @DisplayName("MySQL과 H2의 길이 초과를 길이 초과로 분류하고 컬럼 이름을 요청 필드 이름으로 바꾼다")
    void MySQL과_H2의_길이_초과를_분류하고_컬럼_이름을_필드_이름으로_바꾼다() {
        // given
        DataIntegrityViolationException mysql = wrap(new SQLException(
                "Data truncation: Data too long for column 'place_address' at row 1", "22001", 1406));
        DataIntegrityViolationException h2 = wrap(new SQLException(
                "Value too long for column \"SCHEDULE_NAME CHARACTER VARYING(255)\": \"'aaa' (300)\"", "22001", 22001));

        // when / then
        assertThat(DataIntegrityViolationKind.classify(mysql))
                .isEqualTo(new DataIntegrityViolationKind(DataIntegrityViolationKind.Type.TOO_LONG, "placeAddress"));
        assertThat(DataIntegrityViolationKind.classify(h2))
                .isEqualTo(new DataIntegrityViolationKind(DataIntegrityViolationKind.Type.TOO_LONG, "scheduleName"));
    }

    @Test
    @DisplayName("MySQL·H2 유니크 위반과 Spring 중복 키 예외를 중복으로 분류한다")
    void 유니크_위반과_중복_키_예외를_중복으로_분류한다() {
        assertThat(DataIntegrityViolationKind.classify(wrap(new SQLException("Duplicate entry", "23000", 1062))).type())
                .isEqualTo(DataIntegrityViolationKind.Type.DUPLICATE);
        assertThat(DataIntegrityViolationKind.classify(wrap(new SQLException("Unique index", "23505", 23505))).type())
                .isEqualTo(DataIntegrityViolationKind.Type.DUPLICATE);
        assertThat(DataIntegrityViolationKind.classify(new DuplicateKeyException("dup")).type())
                .isEqualTo(DataIntegrityViolationKind.Type.DUPLICATE);
    }

    @Test
    @DisplayName("NOT NULL·FK 위반이나 원인을 알 수 없는 경우는 서버 데이터 문제로 분류한다")
    void NOT_NULL_FK_위반이나_원인을_알_수_없으면_서버_데이터_문제로_분류한다() {
        assertThat(DataIntegrityViolationKind.classify(wrap(new SQLException("cannot be null", "23000", 1048))).type())
                .isEqualTo(DataIntegrityViolationKind.Type.OTHER);
        assertThat(DataIntegrityViolationKind.classify(new DataIntegrityViolationException("no cause")).type())
                .isEqualTo(DataIntegrityViolationKind.Type.OTHER);
    }

    private static DataIntegrityViolationException wrap(SQLException cause) {
        // Hibernate 예외 한 겹을 흉내 내 원인 사슬을 따라가는지 확인한다.
        return new DataIntegrityViolationException("could not execute statement", new RuntimeException("hibernate", cause));
    }
}
