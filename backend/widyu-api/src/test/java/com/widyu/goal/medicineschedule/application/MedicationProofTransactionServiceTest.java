package com.widyu.goal.medicineschedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.goal.medicineschedule.repository.MedicineScheduleRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.application.SeniorProfileService;
import com.widyu.member.repository.MemberRepository;
import com.widyu.medicine.MedicationProof;
import com.widyu.medicine.MedicineSchedule;
import java.time.LocalTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
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
    @Mock private SeniorProfileService seniorProfileService;

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
    @DisplayName("남은 일정이 있는 인증을 저장하면 10포인트를 즉시 적립한다")
    void 남은_일정이_있는_인증을_저장하면_10포인트를_즉시_적립한다() {
        // given
        Member member = lockedMember();
        MedicineSchedule schedule = MedicineSchedule.create(member, LocalTime.now());
        given(member.getSeniorProfile()).willReturn(mock(SeniorProfile.class));
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(1L, Status.ACTIVE))
                .willReturn(Optional.of(schedule));
        given(medicationProofRepository.countByMemberAndVerifiedAtBetween(eq(member), any(), any())).willReturn(1L);
        given(medicineScheduleRepository.countEffectiveByMemberAndDate(eq(member), eq(Status.ACTIVE), any())).willReturn(3L);
        savedProofHasId(55L);

        // when
        var response = transactionService.verifyMedication(10L, 1L, List.of());

        // then
        assertThat(response.earnedPoints()).isEqualTo(10L);
        then(seniorProfileService).should()
                .addPointsToMember(10L, 10L, "약 복용 인증", "MEDICATION_PROOF:55");
    }

    @Test
    @DisplayName("마지막 남은 일정을 인증하면 보너스를 더한 30포인트를 한 번에 적립한다")
    void 마지막_남은_일정을_인증하면_보너스를_더한_30포인트를_한_번에_적립한다() {
        // given
        Member member = lockedMember();
        MedicineSchedule schedule = MedicineSchedule.create(member, LocalTime.now());
        given(member.getSeniorProfile()).willReturn(mock(SeniorProfile.class));
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(1L, Status.ACTIVE))
                .willReturn(Optional.of(schedule));
        given(medicationProofRepository.countByMemberAndVerifiedAtBetween(eq(member), any(), any())).willReturn(3L);
        given(medicineScheduleRepository.countEffectiveByMemberAndDate(eq(member), eq(Status.ACTIVE), any())).willReturn(3L);
        savedProofHasId(77L);

        // when
        var response = transactionService.verifyMedication(10L, 1L, List.of());

        // then
        assertThat(response.earnedPoints()).isEqualTo(30L);
        then(seniorProfileService).should()
                .addPointsToMember(10L, 30L, "약 복용 인증", "MEDICATION_PROOF:77");
    }

    @Test
    @DisplayName("적립을 마치면 적립 후 잔액을 현재 포인트로 반환한다")
    void 적립을_마치면_적립_후_잔액을_현재_포인트로_반환한다() {
        // given
        Member member = lockedMember();
        SeniorProfile seniorProfile = mock(SeniorProfile.class);
        MedicineSchedule schedule = MedicineSchedule.create(member, LocalTime.now());
        given(member.getSeniorProfile()).willReturn(seniorProfile);
        AtomicLong balance = new AtomicLong(120L);
        given(seniorProfile.getPoints()).willAnswer(invocation -> balance.get());
        willAnswer(invocation -> {
            balance.set(130L);
            return null;
        }).given(seniorProfileService).addPointsToMember(eq(10L), eq(10L), any(), any());
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(1L, Status.ACTIVE))
                .willReturn(Optional.of(schedule));
        given(medicationProofRepository.countByMemberAndVerifiedAtBetween(eq(member), any(), any())).willReturn(1L);
        given(medicineScheduleRepository.countEffectiveByMemberAndDate(eq(member), eq(Status.ACTIVE), any())).willReturn(2L);

        // when
        var response = transactionService.verifyMedication(10L, 1L, List.of());

        // then
        assertThat(response.currentPoints()).isEqualTo(130L);
    }

    @Test
    @DisplayName("시니어 프로필이 없으면 적립 없이 현재 포인트를 0으로 반환한다")
    void 시니어_프로필이_없으면_적립_없이_현재_포인트를_0으로_반환한다() {
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
        then(seniorProfileService).should(never()).addPointsToMember(any(), any(), any(), any());
    }

    @Test
    @DisplayName("오늘 이미 인증한 스케줄을 다시 인증하면 적립하지 않는다")
    void 오늘_이미_인증한_스케줄을_다시_인증하면_적립하지_않는다() {
        // given
        Member member = lockedMember();
        MedicineSchedule schedule = MedicineSchedule.create(member, LocalTime.now());
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(1L, Status.ACTIVE))
                .willReturn(Optional.of(schedule));
        given(medicationProofRepository.existsByMedicineScheduleAndVerifiedAtBetween(eq(schedule), any(), any()))
                .willReturn(true);

        // when & then
        assertThatThrownBy(() -> transactionService.verifyMedication(10L, 1L, List.of()))
                .isInstanceOf(BusinessException.class);
        then(seniorProfileService).should(never()).addPointsToMember(any(), any(), any(), any());
    }

    private void savedProofHasId(Long proofId) {
        willAnswer(invocation -> {
            MedicationProof proof = invocation.getArgument(0);
            ReflectionTestUtils.setField(proof, "id", proofId);
            return proof;
        }).given(medicationProofRepository).saveAndFlush(any(MedicationProof.class));
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
