package com.widyu.goal.medicineschedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.util.MemberUtil;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.goal.medicineschedule.dto.response.MedicationStatus;
import com.widyu.goal.medicineschedule.dto.response.MedicineMonthlyResponse;
import com.widyu.goal.medicineschedule.dto.response.MedicineScheduleDailyResponse;
import com.widyu.goal.medicineschedule.dto.response.MedicineScheduleDailyResponse.ScheduleItem;
import com.widyu.goal.medicineschedule.dto.request.CreateMedicineScheduleRequest;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.goal.medicineschedule.repository.MedicineRepository;
import com.widyu.goal.medicineschedule.repository.MedicineScheduleRepository;
import com.widyu.goal.medicineschedule.dto.request.UpdateMedicineScheduleRequest;
import com.widyu.medicine.MedicationProof;
import com.widyu.medicine.Medicine;
import com.widyu.medicine.MedicineSchedule;
import com.widyu.member.Member;
import com.widyu.member.repository.MemberRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("MedicineScheduleService 일자별 조회 단위 테스트")
class MedicineScheduleServiceTest {

    @Mock private MedicineScheduleRepository medicineScheduleRepository;
    @Mock private MedicineRepository medicineRepository;
    @Mock private MedicationProofRepository medicationProofRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private MemberUtil memberUtil;
    @Mock private FcmService fcmService;

    @InjectMocks private MedicineScheduleService medicineScheduleService;

    private MedicineSchedule scheduleWithId(Long id, LocalTime alarmTime) {
        Member member = mock(Member.class);
        MedicineSchedule schedule = MedicineSchedule.create(member, alarmTime);
        ReflectionTestUtils.setField(schedule, "id", id);
        return schedule;
    }

    @Test
    @DisplayName("지난 날짜를 조회하면 인증 스케줄은 DONE과 인증 이미지, 미인증 스케줄은 MISSED와 null을 반환한다")
    void 지난_날짜_조회하면_인증_스케줄은_DONE과_이미지_미인증은_MISSED와_null을_반영한다() {
        // given
        Long memberId = 1L;
        LocalDate pastDate = LocalDate.of(2020, 1, 1);
        Member targetMember = mock(Member.class);

        MedicineSchedule verified = scheduleWithId(10L, LocalTime.of(8, 0));
        MedicineSchedule notVerified = scheduleWithId(20L, LocalTime.of(20, 0));

        MedicationProof proof = mock(MedicationProof.class);
        given(proof.getMedicineSchedule()).willReturn(verified);
        given(proof.getProofImageUrls()).willReturn(List.of("https://widyu.shop/proof/10.jpg"));

        given(memberRepository.findById(memberId)).willReturn(Optional.of(targetMember));
        given(targetMember.getId()).willReturn(memberId);
        given(medicineScheduleRepository.findEffectiveByMemberAndDateWithDetails(targetMember, Status.ACTIVE, pastDate))
                .willReturn(List.of(verified, notVerified));
        given(medicationProofRepository.findByMemberIdAndDateRange(anyLong(), any(), any()))
                .willReturn(List.of(proof));

        // when
        MedicineScheduleDailyResponse response = medicineScheduleService.getDailySchedules(memberId, pastDate);

        // then
        Map<Long, ScheduleItem> itemByScheduleId = response.medicineSchedules().stream()
                .collect(Collectors.toMap(ScheduleItem::medicineScheduleId, item -> item));
        assertThat(itemByScheduleId.get(10L).status()).isEqualTo(MedicationStatus.DONE);
        assertThat(itemByScheduleId.get(10L).proofImageUrl()).isEqualTo("https://widyu.shop/proof/10.jpg");
        assertThat(itemByScheduleId.get(20L).status()).isEqualTo(MedicationStatus.MISSED);
        assertThat(itemByScheduleId.get(20L).proofImageUrl()).isNull();
    }

    @Test
    @DisplayName("활성 스케줄이 없으면 빈 목록을 반환하고 복용 인증 조회를 하지 않는다")
    void 활성_스케줄이_없으면_빈_목록을_반환하고_인증조회를_생략한다() {
        // given
        Long memberId = 1L;
        LocalDate date = LocalDate.of(2026, 7, 6);
        Member targetMember = mock(Member.class);

        given(memberRepository.findById(memberId)).willReturn(Optional.of(targetMember));
        given(medicineScheduleRepository.findEffectiveByMemberAndDateWithDetails(targetMember, Status.ACTIVE, date))
                .willReturn(List.of());

        // when
        MedicineScheduleDailyResponse response = medicineScheduleService.getDailySchedules(memberId, date);

        // then
        assertThat(response.medicineSchedules()).isEmpty();
        then(medicationProofRepository).should(never()).findByMemberIdAndDateRange(anyLong(), any(), any());
    }

    private UpdateMedicineScheduleRequest updateRequest(String alarmTime) {
        return new UpdateMedicineScheduleRequest(
                alarmTime,
                List.of(new UpdateMedicineScheduleRequest.CategoryItem(
                        "아침약",
                        List.of(new UpdateMedicineScheduleRequest.MedicineItem(
                                "타이레놀", 1.0, null, null, null))))
        );
    }

    private CreateMedicineScheduleRequest createRequest(String alarmTime) {
        return new CreateMedicineScheduleRequest(
                alarmTime,
                List.of(new CreateMedicineScheduleRequest.CategoryItem(
                        "아침약",
                        List.of(new CreateMedicineScheduleRequest.MedicineItem(
                                "타이레놀", 1.0, null, null, null))))
        );
    }

    private MedicineSchedule scheduleEffectiveFrom(Long id, Member member, LocalTime alarmTime, LocalDate effectiveFrom) {
        MedicineSchedule schedule = MedicineSchedule.create(member, alarmTime);
        ReflectionTestUtils.setField(schedule, "id", id);
        ReflectionTestUtils.setField(schedule, "effectiveFrom", effectiveFrom);
        return schedule;
    }

    @Test
    @DisplayName("과거부터 유효한 스케줄을 수정하면 기존 버전은 어제까지 마감되고 새 버전이 생성된다")
    void 과거_유효_스케줄_수정하면_기존_마감_후_새_버전_생성() {
        // given
        Long memberId = 1L;
        Long scheduleId = 100L;
        Member targetMember = mock(Member.class);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));
        given(targetMember.getId()).willReturn(memberId);

        MedicineSchedule existing = scheduleEffectiveFrom(
                scheduleId, targetMember, LocalTime.of(8, 0), LocalDate.now().minusDays(5));
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE))
                .willReturn(Optional.of(existing));
        given(medicineRepository.findByItemName("타이레놀")).willReturn(Optional.of(mock(Medicine.class)));
        given(medicineScheduleRepository.save(any(MedicineSchedule.class)))
                .willAnswer(invocation -> {
                    MedicineSchedule newSchedule = invocation.getArgument(0);
                    ReflectionTestUtils.setField(newSchedule, "id", 101L);
                    return newSchedule;
                });
        MedicationProof proof = MedicationProof.create(existing, targetMember, List.of());
        given(medicationProofRepository.findByMedicineScheduleAndVerifiedAtBetween(any(), any(), any()))
                .willReturn(List.of(proof));

        // when
        medicineScheduleService.updateSchedule(scheduleId, updateRequest("09:00"), memberId);

        // then
        assertThat(existing.getEffectiveTo()).isEqualTo(LocalDate.now().minusDays(1));
        then(medicineScheduleRepository).should().save(any(MedicineSchedule.class));
        assertThat(proof.getMedicineSchedule().getId()).isEqualTo(101L);
        then(targetMember).should().incrementMedicationAlarmRevision();
        then(fcmService).should().sendMessageToUser(
                org.mockito.ArgumentMatchers.eq(memberId), any(FcmSendDto.class));
    }

    @Test
    @DisplayName("오늘 생성된 스케줄을 수정하면 새 버전 없이 그대로 수정된다")
    void 오늘_생성_스케줄_수정은_새_버전_없이_수정된다() {
        // given
        Long memberId = 1L;
        Long scheduleId = 100L;
        Member targetMember = mock(Member.class);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));
        given(targetMember.getId()).willReturn(memberId);

        MedicineSchedule existing = scheduleEffectiveFrom(
                scheduleId, targetMember, LocalTime.of(8, 0), LocalDate.now());
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE))
                .willReturn(Optional.of(existing));
        given(medicineRepository.findByItemName("타이레놀")).willReturn(Optional.of(mock(Medicine.class)));

        // when
        medicineScheduleService.updateSchedule(scheduleId, updateRequest("09:00"), memberId);

        // then
        assertThat(existing.getEffectiveTo()).isNull();
        assertThat(existing.getAlarmTime()).isEqualTo(LocalTime.of(9, 0));
        then(medicineScheduleRepository).should(never()).save(any(MedicineSchedule.class));
        then(targetMember).should().incrementMedicationAlarmRevision();
        ArgumentCaptor<FcmSendDto> messageCaptor = ArgumentCaptor.forClass(FcmSendDto.class);
        then(fcmService).should().sendMessageToUser(org.mockito.ArgumentMatchers.eq(memberId), messageCaptor.capture());
        assertThat(messageCaptor.getValue().data())
                .containsEntry("type", "MEDICATION_SCHEDULE_CHANGED")
                .containsKey("revision");
    }

    @Test
    @DisplayName("스케줄을 삭제하면 오늘부터 중단되도록 어제까지 마감된다")
    void 삭제하면_어제까지_마감되어_과거는_보존된다() {
        // given
        Long memberId = 1L;
        Long scheduleId = 100L;
        Member targetMember = mock(Member.class);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));
        given(targetMember.getId()).willReturn(memberId);

        MedicineSchedule existing = scheduleEffectiveFrom(
                scheduleId, targetMember, LocalTime.of(8, 0), LocalDate.now().minusDays(3));
        given(medicineScheduleRepository.findById(scheduleId)).willReturn(Optional.of(existing));

        // when
        medicineScheduleService.deleteSchedule(scheduleId, memberId);

        // then
        assertThat(existing.getEffectiveTo()).isEqualTo(LocalDate.now().minusDays(1));
        then(targetMember).should().incrementMedicationAlarmRevision();
        then(fcmService).should().sendMessageToUser(
                org.mockito.ArgumentMatchers.eq(memberId), any(FcmSendDto.class));
    }

    @Test
    @DisplayName("스케줄을 생성하면 revision을 증가시키고 설정 변경 FCM을 보낸다")
    void 스케줄을_생성하면_revision을_증가시키고_설정_변경_FCM을_보낸다() {
        // given
        Long memberId = 1L;
        Member targetMember = mock(Member.class);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));
        given(targetMember.getId()).willReturn(memberId);
        given(medicineRepository.findByItemName("타이레놀")).willReturn(Optional.of(mock(Medicine.class)));
        given(medicineScheduleRepository.save(any(MedicineSchedule.class))).willAnswer(invocation -> {
            MedicineSchedule schedule = invocation.getArgument(0);
            ReflectionTestUtils.setField(schedule, "id", 1L);
            return schedule;
        });

        // when
        medicineScheduleService.createSchedule(createRequest("08:00"), memberId);

        // then
        then(targetMember).should().incrementMedicationAlarmRevision();
        then(fcmService).should().sendMessageToUser(
                org.mockito.ArgumentMatchers.eq(memberId), any(FcmSendDto.class));
    }

    @Test
    @DisplayName("이미 종료된 과거 버전을 수정하면 예외가 발생한다")
    void 종료된_과거_버전_수정_시_예외가_발생한다() {
        // given
        Long memberId = 1L;
        Long scheduleId = 100L;
        Member targetMember = mock(Member.class);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));
        given(targetMember.getId()).willReturn(memberId);

        MedicineSchedule closed = scheduleEffectiveFrom(
                scheduleId, targetMember, LocalTime.of(8, 0), LocalDate.now().minusDays(10));
        ReflectionTestUtils.setField(closed, "effectiveTo", LocalDate.now().minusDays(5));
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE))
                .willReturn(Optional.of(closed));

        // when & then
        assertThatThrownBy(() -> medicineScheduleService.updateSchedule(scheduleId, updateRequest("09:00"), memberId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("이미 종료된 과거 버전을 삭제하면 예외가 발생한다")
    void 종료된_과거_버전_삭제_시_예외가_발생한다() {
        // given
        Long memberId = 1L;
        Long scheduleId = 100L;
        Member targetMember = mock(Member.class);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));
        given(targetMember.getId()).willReturn(memberId);

        MedicineSchedule closed = scheduleEffectiveFrom(
                scheduleId, targetMember, LocalTime.of(8, 0), LocalDate.now().minusDays(10));
        ReflectionTestUtils.setField(closed, "effectiveTo", LocalDate.now().minusDays(5));
        given(medicineScheduleRepository.findById(scheduleId)).willReturn(Optional.of(closed));

        // when & then
        assertThatThrownBy(() -> medicineScheduleService.deleteSchedule(scheduleId, memberId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("월별 달성률은 그날 유효했던 스케줄 수를 분모로 계산해 스케줄 시작 전 날짜는 0으로 나온다")
    void 월별_달성률은_날짜별_유효_스케줄_수를_분모로_계산한다() {
        // given: 7월 15일부터 유효한 스케줄 1개, 15일에 인증 1건
        Long memberId = 1L;
        Member targetMember = mock(Member.class);
        given(memberRepository.findById(memberId)).willReturn(Optional.of(targetMember));
        given(targetMember.getId()).willReturn(memberId);

        MedicineSchedule schedule = scheduleEffectiveFrom(1L, targetMember, LocalTime.of(8, 0), LocalDate.of(2026, 7, 15));

        MedicationProof proof = mock(MedicationProof.class);
        given(proof.getMedicineSchedule()).willReturn(schedule);
        given(proof.getVerifiedAt()).willReturn(LocalDateTime.of(2026, 7, 15, 9, 0));

        given(medicineScheduleRepository.findEffectiveByMemberAndDateRange(any(), any(), any(), any()))
                .willReturn(List.of(schedule));
        given(medicationProofRepository.findByMemberIdAndDateRange(anyLong(), any(), any()))
                .willReturn(List.of(proof));

        // when
        MedicineMonthlyResponse response = medicineScheduleService.getMonthlyStats(2026, 7, memberId);

        // then
        List<Double> rates = response.monthlyGoalRates();
        assertThat(rates).hasSize(31);
        assertThat(rates.get(0)).isEqualTo(0.0);   // 7/1: 스케줄 시작 전 → 분모 0 → 0.0
        assertThat(rates.get(14)).isEqualTo(1.0);  // 7/15: 유효 1개 중 1개 인증 → 1.0
        assertThat(rates.get(15)).isEqualTo(0.0);  // 7/16: 유효 1개, 인증 0 → 0.0
    }

    @Test
    @DisplayName("유효하지 않은 스케줄의 인증은 해당 날짜 달성률에 반영하지 않는다")
    void 유효하지_않은_스케줄의_인증은_달성률에서_제외한다() {
        // given
        Long memberId = 1L;
        Member targetMember = mock(Member.class);
        given(memberRepository.findById(memberId)).willReturn(Optional.of(targetMember));
        given(targetMember.getId()).willReturn(memberId);

        MedicineSchedule oldSchedule = scheduleEffectiveFrom(
                1L, targetMember, LocalTime.of(8, 0), LocalDate.of(2026, 7, 1));
        ReflectionTestUtils.setField(oldSchedule, "effectiveTo", LocalDate.of(2026, 7, 14));
        MedicineSchedule currentSchedule = scheduleEffectiveFrom(
                2L, targetMember, LocalTime.of(8, 0), LocalDate.of(2026, 7, 15));

        MedicationProof proof = mock(MedicationProof.class);
        given(proof.getMedicineSchedule()).willReturn(oldSchedule);
        given(proof.getVerifiedAt()).willReturn(LocalDateTime.of(2026, 7, 15, 9, 0));

        given(medicineScheduleRepository.findEffectiveByMemberAndDateRange(any(), any(), any(), any()))
                .willReturn(List.of(oldSchedule, currentSchedule));
        given(medicationProofRepository.findByMemberIdAndDateRange(anyLong(), any(), any()))
                .willReturn(List.of(proof));

        // when
        MedicineMonthlyResponse response = medicineScheduleService.getMonthlyStats(2026, 7, memberId);

        // then
        assertThat(response.monthlyGoalRates().get(14)).isEqualTo(0.0);
    }
}
