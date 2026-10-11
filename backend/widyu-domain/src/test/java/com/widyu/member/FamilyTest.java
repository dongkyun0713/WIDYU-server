package com.widyu.member;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FamilyTest {

    @Test
    @DisplayName("가족 순서 revision을 증가시키면 기존 값보다 1 증가한다")
    void 가족_순서_revision을_증가시키면_1_증가한다() {
        // given
        Family family = Family.createFamily("ABC123");

        // when
        family.incrementOrderRevision();

        // then
        assertThat(family.getFamilyOrderRevision()).isEqualTo(1);
    }
}
