package com.widyu.member.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.global.entity.Status;
import com.widyu.member.Family;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.walk.Walk;
import java.time.LocalDate;
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
@DisplayName("걷기 목표는 있는데 그날 걸음 기록이 없는 회원 조회 테스트")
class SeniorProfileWalkGoalQueryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Autowired private SeniorProfileRepository seniorProfileRepository;
    @Autowired private TestEntityManager entityManager;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @Test
    @DisplayName("기본 목표가 있고 그날 기록이 없는 활성 회원만 조회한다")
    void 기본_목표가_있고_그날_기록이_없는_활성_회원만_조회한다() {
        // given
        Member target = persistSenior("01000000001", "1000001", 6000);
        persistSenior("01000000002", "1000002", null);
        Member walked = persistSenior("01000000003", "1000003", 6000);
        entityManager.persist(Walk.createWithGoal(walked, TODAY, 6000));
        Member yesterdayOnly = persistSenior("01000000004", "1000004", 6000);
        entityManager.persist(Walk.createWithGoal(yesterdayOnly, TODAY.minusDays(1), 6000));
        Member withdrawn = persistSenior("01000000005", "1000005", 6000);
        withdrawn.withdraw();
        entityManager.flush();
        entityManager.clear();

        // when
        List<Member> members = seniorProfileRepository.findMembersWithWalkGoalButNoWalkOn(TODAY, Status.ACTIVE);

        // then
        assertThat(members).extracting(Member::getId)
                .containsExactlyInAnyOrder(target.getId(), yesterdayOnly.getId());
    }

    private Member persistSenior(String phoneNumber, String inviteCode, Integer defaultWalkGoal) {
        Member member = Member.createMember(MemberType.SENIOR, "시니어", phoneNumber);
        entityManager.persist(member);
        Family family = Family.createFamily(inviteCode.substring(1));
        entityManager.persist(family);
        SeniorProfile profile = SeniorProfile.createSeniorProfile(member, family, "서울", inviteCode, LocalDate.of(1950, 1, 1));
        if (defaultWalkGoal != null) {
            profile.updateDefaultWalkGoal(defaultWalkGoal);
        }
        entityManager.persist(profile);
        return member;
    }
}
