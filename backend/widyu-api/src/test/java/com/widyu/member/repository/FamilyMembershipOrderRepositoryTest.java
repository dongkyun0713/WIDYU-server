package com.widyu.member.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.member.Family;
import com.widyu.member.FamilyMembership;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
class FamilyMembershipOrderRepositoryTest {

    @Autowired private FamilyMembershipRepository membershipRepository;
    @Autowired private FamilyRepository familyRepository;
    @Autowired private TestEntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @Test
    @DisplayName("보호자 순서를 조회하면 저장한 위에서 아래 순서로 반환한다")
    void 보호자_순서를_조회하면_저장한_순서로_반환한다() {
        // given
        Family family = entityManager.persistAndFlush(Family.createFamily("ABC123"));
        Member first = entityManager.persistAndFlush(Member.createMember(MemberType.GUARDIAN, "첫째", "01011112222"));
        Member second = entityManager.persistAndFlush(Member.createMember(MemberType.GUARDIAN, "둘째", "01033334444"));
        FamilyMembership later = entityManager.persistAndFlush(FamilyMembership.createMembership(family, first, 3));
        FamilyMembership earlier = entityManager.persistAndFlush(FamilyMembership.createMembership(family, second, 1));
        entityManager.clear();

        // when
        List<FamilyMembership> ordered = membershipRepository.findAllByFamilyIdWithGuardianOrdered(family.getId());
        int maxOrder = membershipRepository.findMaxSortOrderByFamilyId(family.getId());
        Family lockedFamily = familyRepository.findByIdForUpdate(family.getId()).orElseThrow();
        FamilyMembership lockedMember = membershipRepository
                .findByFamilyIdAndGuardianIdForUpdate(family.getId(), second.getId()).orElseThrow();

        // then
        assertThat(ordered).extracting(FamilyMembership::getId).containsExactly(earlier.getId(), later.getId());
        assertThat(maxOrder).isEqualTo(3);
        assertThat(lockedFamily.getFamilyOrderRevision()).isZero();
        assertThat(lockedMember.getId()).isEqualTo(earlier.getId());
    }

    @Test
    @DisplayName("가족 보호자 목록을 잠금 조회하면 membership ID 순서로 반환한다")
    void 가족_보호자_목록을_잠금_조회하면_membership_ID_순서로_반환한다() {
        // given
        Family family = entityManager.persistAndFlush(Family.createFamily("LCK123"));
        Member first = entityManager.persistAndFlush(Member.createMember(MemberType.GUARDIAN, "첫째", "01055556666"));
        Member second = entityManager.persistAndFlush(Member.createMember(MemberType.GUARDIAN, "둘째", "01077778888"));
        FamilyMembership firstMembership = entityManager.persistAndFlush(FamilyMembership.createMembership(family, first, 3));
        FamilyMembership secondMembership = entityManager.persistAndFlush(FamilyMembership.createMembership(family, second, 1));
        entityManager.clear();

        // when
        List<FamilyMembership> locked = membershipRepository.findAllByFamilyIdForUpdate(family.getId());

        // then
        assertThat(locked).extracting(FamilyMembership::getId)
                .containsExactly(firstMembership.getId(), secondMembership.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("선행 조회로 적재한 보호자를 잠금 조회하면 이전 상태를 재사용하고 clear 후에는 최신 상태를 읽는다")
    void 선행_조회로_적재한_보호자를_잠금_조회하면_clear_후_최신_상태를_읽는다() {
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);
        TransactionTemplate innerTransaction = new TransactionTemplate(transactionManager);
        innerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // given
        Long[] ids = outerTransaction.execute(status -> {
            Family family = entityManager.persistAndFlush(Family.createFamily("CLR123"));
            Member guardian = entityManager.persistAndFlush(
                    Member.createMember(MemberType.GUARDIAN, "방장", "01099990000"));
            FamilyMembership membership = entityManager.persistAndFlush(
                    FamilyMembership.createLeaderMembership(family, guardian));
            return new Long[] {family.getId(), guardian.getId(), membership.getId()};
        });

        // when / then
        outerTransaction.executeWithoutResult(status -> {
            FamilyMembership loaded = membershipRepository.findByGuardianId(ids[1]).orElseThrow();
            assertThat(loaded.isLeader()).isTrue();

            innerTransaction.executeWithoutResult(innerStatus -> {
                FamilyMembership changed = membershipRepository.findById(ids[2]).orElseThrow();
                changed.setLeader(false);
            });

            FamilyMembership stale = membershipRepository
                    .findByFamilyIdAndGuardianIdForUpdate(ids[0], ids[1]).orElseThrow();
            assertThat(stale.isLeader()).isTrue();

            entityManager.clear();
            FamilyMembership fresh = membershipRepository
                    .findByFamilyIdAndGuardianIdForUpdate(ids[0], ids[1]).orElseThrow();
            assertThat(fresh.isLeader()).isFalse();
        });
    }

    @Test
    @DisplayName("보호자 구성 변경으로 revision을 증가시키면 저장된 값이 1 증가한다")
    void 보호자_구성을_변경하면_revision이_증가한다() {
        // given
        Family family = entityManager.persistAndFlush(Family.createFamily("XYZ123"));
        entityManager.clear();
        assertThat(membershipRepository.findMaxSortOrderByFamilyId(family.getId())).isEqualTo(-1);

        // when
        int changed = familyRepository.incrementOrderRevision(family.getId());
        entityManager.clear();

        // then
        assertThat(changed).isEqualTo(1);
        assertThat(familyRepository.findById(family.getId()).orElseThrow().getFamilyOrderRevision()).isEqualTo(1);
    }
}
