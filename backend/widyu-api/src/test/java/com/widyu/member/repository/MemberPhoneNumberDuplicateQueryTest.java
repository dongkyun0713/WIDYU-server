package com.widyu.member.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import java.util.List;
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
@DisplayName("전화번호 중복 조회 테스트")
class MemberPhoneNumberDuplicateQueryTest {

    @Autowired private MemberRepository memberRepository;
    @Autowired private TestEntityManager entityManager;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @Test
    @DisplayName("같은 번호의 회원이 이미 둘 이상 저장돼 있으면 단건 조회 예외 없이 두 건을 반환한다")
    void 같은_번호의_회원이_둘_이상이면_두_건을_반환한다() {
        // given
        persist(MemberType.GUARDIAN, "보호자", "01000000001");
        persist(MemberType.SENIOR, "시니어", "01000000001");
        persist(MemberType.SENIOR, "시니어", "01000000001");

        // when
        List<Member> byPhone = memberRepository.findTop2ByPhoneNumber("01000000001");
        List<Member> byPhoneAndName = memberRepository.findTop2ByPhoneNumberAndName("01000000001", "시니어");

        // then
        assertThat(byPhone).hasSize(2);
        assertThat(byPhoneAndName).hasSize(2);
    }

    @Test
    @DisplayName("번호를 본인만 쓰면 다른 회원 사용 여부가 false이고 다른 회원도 쓰면 true다")
    void 본인만_쓰는_번호는_다른_회원_사용으로_보지_않는다() {
        // given
        Member owner = persist(MemberType.SENIOR, "시니어", "01000000001");
        Member other = persist(MemberType.GUARDIAN, "보호자", "01000000002");

        // when & then
        assertThat(memberRepository.existsByPhoneNumberAndIdNot("01000000001", owner.getId())).isFalse();
        assertThat(memberRepository.existsByPhoneNumberAndIdNot("01000000002", owner.getId())).isTrue();
        assertThat(memberRepository.existsByPhoneNumberAndIdNot("01000000002", other.getId())).isFalse();
    }

    @Test
    @DisplayName("탈퇴해 개인정보가 마스킹된 회원의 번호는 사용 중으로 보지 않는다")
    void 탈퇴로_마스킹된_회원의_번호는_사용_중으로_보지_않는다() {
        // given
        Member withdrawn = Member.createMember(MemberType.GUARDIAN, "보호자", "01000000001");
        withdrawn.maskPersonalInfo();
        withdrawn.withdraw();
        entityManager.persistAndFlush(withdrawn);

        // when & then
        assertThat(memberRepository.existsByPhoneNumber("01000000001")).isFalse();
    }

    private Member persist(MemberType type, String name, String phoneNumber) {
        Member member = Member.createMember(type, name, phoneNumber);
        entityManager.persistAndFlush(member);
        return member;
    }
}
