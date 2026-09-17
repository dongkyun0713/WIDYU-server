package com.widyu.goal.medicineschedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.goal.medicineschedule.repository.MedicineScheduleRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.MemberRepository;
import com.widyu.medicine.MedicationProof;
import com.widyu.medicine.MedicineSchedule;
import java.time.LocalTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("MedicationProofTransactionService 단위 테스트")
class MedicationProofTransactionServiceTest {

    @Mock private MedicationProofRepository medicationProofRepository;
    @Mock private MedicineScheduleRepository medicineScheduleRepository;
    @Mock private MemberRepository memberRepository;

    @InjectMocks private MedicationProofTransactionService transactionService;

    @Test
    @DisplayName("인증을 저장하면 회원 잠금 뒤 revision을 증가시킨다")
    void 인증을_저장하면_회원_잠금_뒤_revision을_증가시킨다() {
        // given
        Member member = member();
        MedicineSchedule schedule = MedicineSchedule.create(member, LocalTime.now());
        given(memberRepository.findByIdForUpdate(10L)).willReturn(Optional.of(member));
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(1L, Status.ACTIVE))
                .willReturn(Optional.of(schedule));
        given(medicationProofRepository.countByMemberAndVerifiedAtBetween(eq(member), any(), any())).willReturn(1L);
        given(medicineScheduleRepository.countEffectiveByMemberAndDate(eq(member), eq(Status.ACTIVE), any())).willReturn(2L);

        // when
        transactionService.verifyMedication(10L, 1L, List.of("https://cdn/proof.jpg"));

        // then
        assertThat(member.getMedicationAlarmRevision()).isEqualTo(1L);
        then(memberRepository).should().findByIdForUpdate(10L);
    }

    @Test
    @DisplayName("중복 인증 저장이 실패하면 revision을 증가시키지 않는다")
    void 중복_인증_저장에_실패하면_revision을_증가시키지_않는다() {
        // given
        Member member = member();
        MedicineSchedule schedule = MedicineSchedule.create(member, LocalTime.now());
        given(memberRepository.findByIdForUpdate(10L)).willReturn(Optional.of(member));
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(1L, Status.ACTIVE))
                .willReturn(Optional.of(schedule));
        given(medicationProofRepository.saveAndFlush(any(MedicationProof.class)))
                .willThrow(new DataIntegrityViolationException("duplicate proof"));

        // when & then
        assertThatThrownBy(() -> transactionService.verifyMedication(10L, 1L, List.of()))
                .isInstanceOf(BusinessException.class);
        assertThat(member.getMedicationAlarmRevision()).isZero();
    }

    @Test
    @DisplayName("오늘 유효하지 않은 과거 스케줄은 복용 인증할 수 없다")
    void 오늘_유효하지_않은_과거_스케줄은_복용_인증할_수_없다() {
        // given
        Member member = lockedMember();
        MedicineSchedule schedule = MedicineSchedule.create(member, LocalTime.now());
        ReflectionTestUtils.setField(schedule, "effectiveFrom", LocalDate.now().minusDays(10));
        ReflectionTestUtils.setField(schedule, "effectiveTo", LocalDate.now().minusDays(1));
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(1L, Status.ACTIVE))
                .willReturn(Optional.of(schedule));

        // when & then
        assertThatThrownBy(() -> transactionService.verifyMedication(10L, 1L, List.of()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("남은 일정이 있으면 적립 예정 포인트를 10으로 반환한다")
    void 남은_일정이_있으면_적립_예정_포인트를_10으로_반환한다() {
        // given
        Member member = lockedMember();
        MedicineSchedule schedule = MedicineSchedule.create(member, LocalTime.now());
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(1L, Status.ACTIVE))
                .willReturn(Optional.of(schedule));
        given(medicationProofRepository.countByMemberAndVerifiedAtBetween(eq(member), any(), any())).willReturn(1L);
        given(medicineScheduleRepository.countEffectiveByMemberAndDate(eq(member), eq(Status.ACTIVE), any())).willReturn(3L);

        // when
        var response = transactionService.verifyMedication(10L, 1L, List.of());

        // then
        assertThat(response.earnedPoints()).isEqualTo(10L);
    }

    @Test
    @DisplayName("마지막 남은 일정을 인증하면 적립 예정 포인트를 30으로 반환한다")
    void 마지막_남은_일정을_인증하면_적립_예정_포인트를_30으로_반환한다() {
        // given
        Member member = lockedMember();
        MedicineSchedule schedule = MedicineSchedule.create(member, LocalTime.now());
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(1L, Status.ACTIVE))
                .willReturn(Optional.of(schedule));
        given(medicationProofRepository.countByMemberAndVerifiedAtBetween(eq(member), any(), any())).willReturn(3L);
        given(medicineScheduleRepository.countEffectiveByMemberAndDate(eq(member), eq(Status.ACTIVE), any())).willReturn(3L);

        // when
        var response = transactionService.verifyMedication(10L, 1L, List.of());

        // then
        assertThat(response.earnedPoints()).isEqualTo(30L);
    }

    @Test
    @DisplayName("시니어 프로필의 현재 포인트를 반환한다")
    void 시니어_프로필의_현재_포인트를_반환한다() {
        // given
        Member member = lockedMember();
        SeniorProfile seniorProfile = mock(SeniorProfile.class);
        MedicineSchedule schedule = MedicineSchedule.create(member, LocalTime.now());
        given(member.getSeniorProfile()).willReturn(seniorProfile);
        given(seniorProfile.getPoints()).willReturn(120L);
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(1L, Status.ACTIVE))
                .willReturn(Optional.of(schedule));
        given(medicationProofRepository.countByMemberAndVerifiedAtBetween(eq(member), any(), any())).willReturn(1L);
        given(medicineScheduleRepository.countEffectiveByMemberAndDate(eq(member), eq(Status.ACTIVE), any())).willReturn(2L);

        // when
        var response = transactionService.verifyMedication(10L, 1L, List.of());

        // then
        assertThat(response.currentPoints()).isEqualTo(120L);
    }

    @Test
    @DisplayName("시니어 프로필이 없으면 현재 포인트를 0으로 반환한다")
    void 시니어_프로필이_없으면_현재_포인트를_0으로_반환한다() {
        // given
        Member member = lockedMember();
        MedicineSchedule schedule = MedicineSchedule.create(member, LocalTime.now());
        given(member.getSeniorProfile()).willReturn(null);
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(1L, Status.ACTIVE))
                .willReturn(Optional.of(schedule));
        given(medicationProofRepository.countByMemberAndVerifiedAtBetween(eq(member), any(), any())).willReturn(1L);
        given(medicineScheduleRepository.countEffectiveByMemberAndDate(eq(member), eq(Status.ACTIVE), any())).willReturn(2L);

        // when
        var response = transactionService.verifyMedication(10L, 1L, List.of());

        // then
        assertThat(response.currentPoints()).isZero();
    }

    private Member member() {
        Member member = Member.createMember(MemberType.SENIOR, "부모님", "01012345678");
        ReflectionTestUtils.setField(member, "id", 10L);
        return member;
    }

    private Member lockedMember() {
        Member member = mock(Member.class);
        given(member.getId()).willReturn(10L);
        given(memberRepository.findByIdForUpdate(10L)).willReturn(Optional.of(member));
        return member;
    }
}
