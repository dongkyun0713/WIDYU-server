package com.widyu.goal.medicineschedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.global.util.MemberUtil;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.goal.medicineschedule.dto.response.MedicationProofResponse;
import com.widyu.goal.medicineschedule.repository.MedicineScheduleRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.MemberRepository;
import com.widyu.medicine.MedicationProof;
import com.widyu.medicine.MedicineSchedule;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@DisplayName("MedicationProofService 복용 인증 단위 테스트")
class MedicationProofServiceTest {

    @Mock private MedicationProofRepository medicationProofRepository;
    @Mock private MedicineScheduleRepository medicineScheduleRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private MemberUtil memberUtil;
    @Mock private S3Service s3Service;

    @InjectMocks private MedicationProofService medicationProofService;

    private void lockMember(Member member) {
        given(memberRepository.findByIdForUpdate(member.getId())).willReturn(Optional.of(member));
    }

    @Test
    @DisplayName("오늘 유효하지 않은 과거 스케줄로 복용 인증하면 예외가 발생한다")
    void 오늘_유효하지_않은_과거_스케줄은_복용_인증할_수_없다() {
        // given
        Long scheduleId = 1L;
        Member member = mock(Member.class);
        given(member.getId()).willReturn(10L);
        given(memberUtil.getCurrentMember()).willReturn(member);
        lockMember(member);

        MedicineSchedule closedSchedule = MedicineSchedule.create(member, LocalTime.now());
        ReflectionTestUtils.setField(closedSchedule, "effectiveFrom", LocalDate.now().minusDays(10));
        ReflectionTestUtils.setField(closedSchedule, "effectiveTo", LocalDate.now().minusDays(1));

        given(medicineScheduleRepository.findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE))
                .willReturn(Optional.of(closedSchedule));

        // when & then
        assertThatThrownBy(() -> medicationProofService.verifyMedication(scheduleId, List.of()))
                .isInstanceOf(BusinessException.class);
        then(medicationProofRepository).should(never()).save(org.mockito.ArgumentMatchers.any(MedicationProof.class));
    }

    @Test
    @DisplayName("아직 남은 복용 일정이 있는 상태로 인증하면 적립 예정 포인트가 10이다")
    void 남은_일정이_있으면_10포인트가_적립될_예정이다() {
        // given
        Long scheduleId = 1L;
        Member member = mock(Member.class);
        given(member.getId()).willReturn(10L);
        given(memberUtil.getCurrentMember()).willReturn(member);
        lockMember(member);

        MedicineSchedule schedule = MedicineSchedule.create(member, LocalTime.now());
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE))
                .willReturn(Optional.of(schedule));
        given(medicationProofRepository.countByMemberAndVerifiedAtBetween(eq(member), any(), any()))
                .willReturn(1L);
        given(medicineScheduleRepository.countEffectiveByMemberAndDate(eq(member), eq(Status.ACTIVE), any()))
                .willReturn(3L);

        // when
        MedicationProofResponse response = medicationProofService.verifyMedication(scheduleId, List.of());

        // then
        assertThat(response.earnedPoints()).isEqualTo(10L);
        then(member).should().incrementMedicationAlarmRevision();
    }

    @Test
    @DisplayName("그날 마지막 남은 일정을 인증하면 완료 보너스가 더해져 적립 예정 포인트가 30이다")
    void 마지막_일정을_인증하면_30포인트가_적립될_예정이다() {
        // given
        Long scheduleId = 2L;
        Member member = mock(Member.class);
        given(member.getId()).willReturn(10L);
        given(memberUtil.getCurrentMember()).willReturn(member);
        lockMember(member);

        MedicineSchedule schedule = MedicineSchedule.create(member, LocalTime.now());
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE))
                .willReturn(Optional.of(schedule));
        given(medicationProofRepository.countByMemberAndVerifiedAtBetween(eq(member), any(), any()))
                .willReturn(3L);
        given(medicineScheduleRepository.countEffectiveByMemberAndDate(eq(member), eq(Status.ACTIVE), any()))
                .willReturn(3L);

        // when
        MedicationProofResponse response = medicationProofService.verifyMedication(scheduleId, List.of());

        // then
        assertThat(response.earnedPoints()).isEqualTo(30L);
    }

    @Test
    @DisplayName("복용 인증하면 이번 적립분이 반영되지 않은 현재 보유 포인트를 반환한다")
    void 인증하면_현재_보유_포인트를_반환한다() {
        // given
        Long scheduleId = 3L;
        SeniorProfile seniorProfile = mock(SeniorProfile.class);
        given(seniorProfile.getPoints()).willReturn(120L);
        Member member = mock(Member.class);
        given(member.getId()).willReturn(10L);
        given(member.getSeniorProfile()).willReturn(seniorProfile);
        given(memberUtil.getCurrentMember()).willReturn(member);
        lockMember(member);

        MedicineSchedule schedule = MedicineSchedule.create(member, LocalTime.now());
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE))
                .willReturn(Optional.of(schedule));
        given(medicationProofRepository.countByMemberAndVerifiedAtBetween(eq(member), any(), any()))
                .willReturn(1L);
        given(medicineScheduleRepository.countEffectiveByMemberAndDate(eq(member), eq(Status.ACTIVE), any()))
                .willReturn(2L);

        // when
        MedicationProofResponse response = medicationProofService.verifyMedication(scheduleId, List.of());

        // then
        assertThat(response.currentPoints()).isEqualTo(120L);
    }

    @Test
    @DisplayName("시니어 프로필이 없는 회원이 복용 인증하면 현재 보유 포인트를 0으로 반환한다")
    void 시니어_프로필이_없으면_현재_보유_포인트는_0이다() {
        // given
        Long scheduleId = 4L;
        Member member = mock(Member.class);
        given(member.getId()).willReturn(10L);
        given(member.getSeniorProfile()).willReturn(null);
        given(memberUtil.getCurrentMember()).willReturn(member);
        lockMember(member);

        MedicineSchedule schedule = MedicineSchedule.create(member, LocalTime.now());
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE))
                .willReturn(Optional.of(schedule));
        given(medicationProofRepository.countByMemberAndVerifiedAtBetween(eq(member), any(), any()))
                .willReturn(1L);
        given(medicineScheduleRepository.countEffectiveByMemberAndDate(eq(member), eq(Status.ACTIVE), any()))
                .willReturn(2L);

        // when
        MedicationProofResponse response = medicationProofService.verifyMedication(scheduleId, List.of());

        // then
        assertThat(response.currentPoints()).isZero();
    }

    @Test
    @DisplayName("중복 인증 저장이 실패하면 복약 알람 revision을 증가시키지 않는다")
    void 중복_인증_저장에_실패하면_revision을_증가시키지_않는다() {
        // given
        Long scheduleId = 5L;
        Member member = Member.createMember(MemberType.SENIOR, "부모님", "01012345678");
        ReflectionTestUtils.setField(member, "id", 10L);
        MedicineSchedule schedule = MedicineSchedule.create(member, LocalTime.now());
        given(memberUtil.getCurrentMember()).willReturn(member);
        lockMember(member);
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE))
                .willReturn(Optional.of(schedule));
        given(medicationProofRepository.existsByMedicineScheduleAndVerifiedAtBetween(eq(schedule), any(), any()))
                .willReturn(false);
        given(medicationProofRepository.saveAndFlush(any(MedicationProof.class)))
                .willThrow(new DataIntegrityViolationException("duplicate proof"));

        // when & then
        assertThatThrownBy(() -> medicationProofService.verifyMedication(scheduleId, List.of()))
                .isInstanceOf(BusinessException.class);
        assertThat(member.getMedicationAlarmRevision()).isZero();
    }
}
