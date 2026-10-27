package com.widyu.member.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
@DisplayName("빈 전화번호 채우기 조건부 갱신 테스트")
class MemberPhoneNumberFillTest {

    @Autowired private MemberRepository memberRepository;
    @Autowired private TestEntityManager entityManager;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @Test
    @DisplayName("전화번호가 없는 회원에게 채우면 1건을 갱신하고 번호가 저장된다")
    void 전화번호가_없는_회원에게_채우면_번호가_저장된다() {
        // given
        Member member = persistGuardian(null);

        // when
        int updated = memberRepository.fillPhoneNumberIfEmpty(member.getId(), "01099999999");

        // then
        entityManager.clear();
        assertThat(updated).isEqualTo(1);
        assertThat(entityManager.find(Member.class, member.getId()).getPhoneNumber()).isEqualTo("01099999999");
    }

    @Test
    @DisplayName("전화번호가 이미 있는 회원에게 채우면 갱신하지 않고 기존 번호가 남는다")
    void 전화번호가_이미_있는_회원에게_채우면_기존_번호가_남는다() {
        // given
        Member member = persistGuardian("01011111111");

        // when
        int updated = memberRepository.fillPhoneNumberIfEmpty(member.getId(), "01099999999");

        // then
        entityManager.clear();
        assertThat(updated).isZero();
        assertThat(entityManager.find(Member.class, member.getId()).getPhoneNumber()).isEqualTo("01011111111");
    }

    private Member persistGuardian(String phoneNumber) {
        Member member = Member.createMember(MemberType.GUARDIAN, "보호자", phoneNumber);
        entityManager.persistAndFlush(member);
        return member;
    }
}
