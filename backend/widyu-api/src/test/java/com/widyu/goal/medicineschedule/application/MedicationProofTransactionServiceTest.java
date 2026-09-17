package com.widyu.goal.medicineschedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.goal.medicineschedule.repository.MedicineScheduleRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import com.widyu.medicine.MedicationProof;
import com.widyu.medicine.MedicineSchedule;
import java.time.LocalTime;
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

    private Member member() {
        Member member = Member.createMember(MemberType.SENIOR, "부모님", "01012345678");
        ReflectionTestUtils.setField(member, "id", 10L);
        return member;
    }
}
