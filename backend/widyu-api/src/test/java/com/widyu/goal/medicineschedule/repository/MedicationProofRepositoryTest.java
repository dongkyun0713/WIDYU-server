package com.widyu.goal.medicineschedule.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.medicine.MedicationProof;
import com.widyu.medicine.MedicineSchedule;
import com.widyu.member.Member;
import com.widyu.member.Family;
import com.widyu.member.MemberType;
import com.widyu.member.PointHistory;
import com.widyu.member.SeniorProfile;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
@DisplayName("MedicationProofRepository 기간 조회 테스트")
class MedicationProofRepositoryTest {

    @Autowired private MedicationProofRepository medicationProofRepository;
    @Autowired private TestEntityManager entityManager;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    private Member persistSenior(String phoneNumber) {
        Member member = Member.createMember(MemberType.SENIOR, "부모님", phoneNumber);
        entityManager.persistAndFlush(member);
        return member;
    }

    private MedicineSchedule persistSchedule(Member member, LocalDate effectiveFrom) {
        MedicineSchedule schedule = MedicineSchedule.create(member, LocalTime.of(8, 0));
        ReflectionTestUtils.setField(schedule, "effectiveFrom", effectiveFrom);
        entityManager.persistAndFlush(schedule);
        return schedule;
    }

    private MedicationProof persistProof(MedicineSchedule schedule, Member member, LocalDateTime verifiedAt) {
        MedicationProof proof = MedicationProof.create(schedule, member, List.of());
        ReflectionTestUtils.setField(proof, "verifiedAt", verifiedAt);
        entityManager.persistAndFlush(proof);
        return proof;
    }

    // 지금은 저장 시 verifiedAt의 날짜로 채워지므로, 두 값이 다른 행은 직접 바꿔 만든다.
    private void setVerifiedDate(MedicationProof proof, LocalDate verifiedDate) {
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE medication_proof SET verified_date = ?1 WHERE id = ?2")
                .setParameter(1, verifiedDate)
                .setParameter(2, proof.getId())
                .executeUpdate();
        entityManager.clear();
    }

    @Test
    @DisplayName("본인의 기간 내 인증만 조회하고 다른 회원·기간 밖 인증은 제외한다")
    void 본인의_기간_내_인증만_조회한다() {
        // given
        Member member = persistSenior("01011112222");
        Member other = persistSenior("01033334444");
        MedicineSchedule schedule = persistSchedule(member, LocalDate.of(2026, 8, 1));
        MedicineSchedule otherSchedule = persistSchedule(other, LocalDate.of(2026, 8, 1));

        MedicationProof inRange = persistProof(schedule, member, LocalDateTime.of(2026, 8, 10, 8, 5));
        persistProof(schedule, member, LocalDateTime.of(2026, 7, 31, 8, 5));   // 기간 밖
        persistProof(otherSchedule, other, LocalDateTime.of(2026, 8, 10, 8, 5)); // 다른 회원

        // when
        List<MedicationProof> proofs = medicationProofRepository.findByMemberIdAndDateRange(
                member.getId(), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 14));

        // then
        assertThat(proofs).extracting(MedicationProof::getId).containsExactly(inRange.getId());
    }

    @Test
    @DisplayName("날짜 조회는 인증 시각이 아니라 복용 기준일로 그날의 인증을 고른다")
    void 날짜_조회는_인증_시각이_아니라_복용_기준일로_그날의_인증을_고른다() {
        // given: 8월 11일 00:05에 했지만 복용 기준일이 8월 10일인 인증(자정을 넘는 알람 창)
        Member member = persistSenior("01099990000");
        MedicineSchedule schedule = persistSchedule(member, LocalDate.of(2026, 8, 1));
        MedicationProof proof = persistProof(schedule, member, LocalDateTime.of(2026, 8, 11, 0, 5));
        setVerifiedDate(proof, LocalDate.of(2026, 8, 10));
        LocalDate doseDate = LocalDate.of(2026, 8, 10);
        LocalDate verifiedAtDate = LocalDate.of(2026, 8, 11);

        // when & then
        assertThat(medicationProofRepository.findByMemberIdAndDateRange(member.getId(), doseDate, doseDate))
                .extracting(MedicationProof::getId).containsExactly(proof.getId());
        assertThat(medicationProofRepository.findByMemberIdAndDateRange(member.getId(), verifiedAtDate, verifiedAtDate))
                .isEmpty();
        assertThat(medicationProofRepository.findVerifiedScheduleIds(List.of(schedule.getId()), doseDate))
                .containsExactly(schedule.getId());
        assertThat(medicationProofRepository.findVerifiedScheduleIds(List.of(schedule.getId()), verifiedAtDate))
                .isEmpty();
        assertThat(medicationProofRepository.findVerifiedScheduleIdsByMemberAndDate(member.getId(), doseDate))
                .containsExactly(schedule.getId());
        assertThat(medicationProofRepository.existsByMedicineScheduleAndVerifiedDate(schedule, doseDate)).isTrue();
        assertThat(medicationProofRepository.existsByMedicineScheduleAndVerifiedDate(schedule, verifiedAtDate)).isFalse();
        assertThat(medicationProofRepository.countByMemberAndVerifiedDate(member, doseDate)).isEqualTo(1L);
        assertThat(medicationProofRepository.countByMemberAndVerifiedDate(member, verifiedAtDate)).isZero();
    }

    @Test
    @DisplayName("같은 날 동일 스케줄에 인증을 두 건 저장하면 예외가 발생한다")
    void 같은_날_동일_스케줄에_중복_인증하면_예외가_발생한다() {
        // given
        Member member = persistSenior("01055556666");
        MedicineSchedule schedule = persistSchedule(member, LocalDate.of(2026, 8, 1));
        persistProof(schedule, member, LocalDateTime.of(2026, 8, 10, 8, 5));

        MedicationProof duplicate = MedicationProof.create(schedule, member, List.of());
        ReflectionTestUtils.setField(duplicate, "verifiedAt", LocalDateTime.of(2026, 8, 10, 8, 25));

        // when & then: 알람 허용창 안이라 시각은 다르지만 같은 날짜다.
        // 서비스와 동일하게 리포지토리로 저장해 Spring 예외 변환까지 확인한다.
        assertThatThrownBy(() -> medicationProofRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("날짜가 다르거나 스케줄이 다르면 중복 제약에 걸리지 않는다")
    void 날짜나_스케줄이_다르면_중복_제약에_걸리지_않는다() {
        // given
        Member member = persistSenior("01077778888");
        MedicineSchedule schedule = persistSchedule(member, LocalDate.of(2026, 8, 1));
        MedicineSchedule anotherSchedule = persistSchedule(member, LocalDate.of(2026, 8, 1));
        persistProof(schedule, member, LocalDateTime.of(2026, 8, 10, 8, 5));

        // when
        MedicationProof nextDay = persistProof(schedule, member, LocalDateTime.of(2026, 8, 11, 8, 5));
        MedicationProof otherSchedule = persistProof(anotherSchedule, member, LocalDateTime.of(2026, 8, 10, 8, 5));

        // then
        assertThat(nextDay.getId()).isNotNull();
        assertThat(otherSchedule.getId()).isNotNull();
    }

    @Test
    @DisplayName("그날 인증 중 보너스를 포함한 적립 이력이 있을 때만 완주 보너스를 받은 날로 판정한다")
    void 그날_인증_중_보너스를_포함한_적립_이력이_있을_때만_완주_보너스를_받은_날로_판정한다() {
        // given
        Member member = persistSenior("01012121212");
        SeniorProfile profile = persistSeniorProfile(member);
        MedicineSchedule morning = persistSchedule(member, LocalDate.of(2026, 8, 1));
        MedicineSchedule noon = persistSchedule(member, LocalDate.of(2026, 8, 1));
        MedicationProof bonusDay = persistProof(morning, member, LocalDateTime.of(2026, 8, 10, 9, 0));
        MedicationProof plainDay = persistProof(morning, member, LocalDateTime.of(2026, 8, 11, 9, 0));
        MedicationProof plainDayNoon = persistProof(noon, member, LocalDateTime.of(2026, 8, 11, 12, 0));
        entityManager.persist(PointHistory.earn(profile, 30L, "약 복용 인증", "MEDICATION_PROOF:" + bonusDay.getId()));
        entityManager.persist(PointHistory.earn(profile, 10L, "약 복용 인증", "MEDICATION_PROOF:" + plainDay.getId()));
        entityManager.persist(PointHistory.earn(profile, 10L, "약 복용 인증", "MEDICATION_PROOF:" + plainDayNoon.getId()));
        entityManager.flush();

        // when & then
        assertThat(medicationProofRepository.existsCompletionBonusByMemberAndVerifiedDate(
                member, LocalDate.of(2026, 8, 10), "MEDICATION_PROOF:", 10L)).isTrue();
        assertThat(medicationProofRepository.existsCompletionBonusByMemberAndVerifiedDate(
                member, LocalDate.of(2026, 8, 11), "MEDICATION_PROOF:", 10L)).isFalse();
        assertThat(medicationProofRepository.existsCompletionBonusByMemberAndVerifiedDate(
                member, LocalDate.of(2026, 8, 12), "MEDICATION_PROOF:", 10L)).isFalse();
    }

    @Test
    @DisplayName("다른 회원이 같은 날 받은 완주 보너스는 내 보너스 지급 여부에 영향을 주지 않는다")
    void 다른_회원이_같은_날_받은_완주_보너스는_내_보너스_지급_여부에_영향을_주지_않는다() {
        // given
        Member member = persistSenior("01034343434");
        Member other = persistSenior("01056565656");
        SeniorProfile otherProfile = persistSeniorProfile(other);
        persistProof(persistSchedule(member, LocalDate.of(2026, 8, 1)), member, LocalDateTime.of(2026, 8, 10, 9, 0));
        MedicationProof otherProof = persistProof(persistSchedule(other, LocalDate.of(2026, 8, 1)), other,
                LocalDateTime.of(2026, 8, 10, 9, 0));
        entityManager.persist(PointHistory.earn(otherProfile, 30L, "약 복용 인증", "MEDICATION_PROOF:" + otherProof.getId()));
        entityManager.flush();

        // when
        boolean granted = medicationProofRepository.existsCompletionBonusByMemberAndVerifiedDate(
                member, LocalDate.of(2026, 8, 10), "MEDICATION_PROOF:", 10L);

        // then
        assertThat(granted).isFalse();
    }

    private SeniorProfile persistSeniorProfile(Member member) {
        String code = String.valueOf(member.getId() + 100000L);
        Family family = Family.createFamily(code.substring(0, 6));
        entityManager.persist(family);
        SeniorProfile profile = SeniorProfile.createSeniorProfile(member, family, "서울", code.substring(0, 6) + "7",
                LocalDate.of(1950, 1, 1));
        entityManager.persist(profile);
        return profile;
    }
}
