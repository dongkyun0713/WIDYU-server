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
import com.widyu.goal.medicineschedule.dto.response.MedicineScheduleIdResponse;
import com.widyu.goal.medicineschedule.dto.response.MedicineScheduleChangeResponse;
import com.widyu.goal.medicineschedule.dto.response.MedicineHomeResponse;
import com.widyu.goal.medicineschedule.dto.request.CreateMedicineScheduleRequest;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.goal.medicineschedule.repository.MedicineRepository;
import com.widyu.goal.medicineschedule.repository.MedicineScheduleRepository;
import com.widyu.goal.medicineschedule.dto.request.UpdateMedicineScheduleRequest;
import com.widyu.medicine.MedicationProof;
import com.widyu.medicine.Medicine;
import com.widyu.medicine.MedicineSchedule;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
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
        MedicineSchedule schedule = MedicineSchedule.create(member, alarmTime, effectiveFrom);
        ReflectionTestUtils.setField(schedule, "id", id);
        return schedule;
    }

    private Member seniorWithId(Long id) {
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    private LocalDate today() {
        return LocalDate.now();
    }

    @Test
    @DisplayName("오늘 유효한 스케줄을 수정하면 오늘 버전과 인증을 보존하고 내일 버전을 만든다")
    void 오늘_유효한_스케줄을_수정하면_오늘_버전과_인증을_보존하고_내일_버전을_만든다() {
        // given
        Long memberId = 1L;
        Long scheduleId = 100L;
        Member targetMember = seniorWithId(memberId);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));

        MedicineSchedule existing = scheduleEffectiveFrom(
                scheduleId, targetMember, LocalTime.of(8, 0), today().minusDays(5));
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

        // when
        MedicineScheduleIdResponse response = medicineScheduleService.updateSchedule(
                scheduleId, updateRequest("09:00"), memberId);

        // then
        assertThat(existing.getEffectiveTo()).isEqualTo(today());
        assertThat(existing.isEffectiveOn(today())).isTrue();
        assertThat(existing.getAlarmTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(proof.getMedicineSchedule()).isSameAs(existing);
        ArgumentCaptor<MedicineSchedule> scheduleCaptor = ArgumentCaptor.forClass(MedicineSchedule.class);
        then(medicineScheduleRepository).should().save(scheduleCaptor.capture());
        assertThat(scheduleCaptor.getValue().getEffectiveFrom()).isEqualTo(today().plusDays(1));
        assertThat(scheduleCaptor.getValue().getAlarmTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(response.medicineScheduleId()).isEqualTo(101L);
        assertThat(response.scheduleRevision()).isEqualTo(targetMember.getMedicationAlarmRevision()).isEqualTo(1);
        assertThat(response.effectiveFromDate()).isEqualTo(today().plusDays(1));
        then(medicationProofRepository).should(never())
                .findByMedicineScheduleAndVerifiedAtBetween(any(), any(), any());
        then(fcmService).should().sendMessageToUser(
                org.mockito.ArgumentMatchers.eq(memberId), any(FcmSendDto.class));
    }

    @Test
    @DisplayName("오늘 시작한 스케줄을 수정하면 오늘 버전을 마감하고 내일 버전을 만든다")
    void 오늘_시작한_스케줄을_수정하면_오늘_버전을_마감하고_내일_버전을_만든다() {
        // given
        Long memberId = 1L;
        Long scheduleId = 100L;
        Member targetMember = seniorWithId(memberId);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));

        MedicineSchedule existing = scheduleEffectiveFrom(
                scheduleId, targetMember, LocalTime.of(8, 0), today());
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE))
                .willReturn(Optional.of(existing));
        given(medicineRepository.findByItemName("타이레놀")).willReturn(Optional.of(mock(Medicine.class)));
        given(medicineScheduleRepository.save(any(MedicineSchedule.class))).willAnswer(invocation -> {
            MedicineSchedule schedule = invocation.getArgument(0);
            ReflectionTestUtils.setField(schedule, "id", 101L);
            return schedule;
        });

        // when
        MedicineScheduleIdResponse response = medicineScheduleService.updateSchedule(
                scheduleId, updateRequest("09:00"), memberId);

        // then
        assertThat(existing.getEffectiveTo()).isEqualTo(today());
        assertThat(existing.getAlarmTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(response.medicineScheduleId()).isEqualTo(101L);
        assertThat(response.effectiveFromDate()).isEqualTo(today().plusDays(1));
        ArgumentCaptor<FcmSendDto> messageCaptor = ArgumentCaptor.forClass(FcmSendDto.class);
        then(fcmService).should().sendMessageToUser(org.mockito.ArgumentMatchers.eq(memberId), messageCaptor.capture());
        assertThat(messageCaptor.getValue().data())
                .containsEntry("type", "MEDICATION_SCHEDULE_CHANGED")
                .containsKey("revision");
    }

    @Test
    @DisplayName("오늘 유효한 스케줄을 삭제하면 오늘 알람을 유지하고 내일부터 중단한다")
    void 오늘_유효한_스케줄을_삭제하면_오늘_알람을_유지하고_내일부터_중단한다() {
        // given
        Long memberId = 1L;
        Long scheduleId = 100L;
        Member targetMember = seniorWithId(memberId);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));

        MedicineSchedule existing = scheduleEffectiveFrom(
                scheduleId, targetMember, LocalTime.of(8, 0), today().minusDays(3));
        given(medicineScheduleRepository.findById(scheduleId)).willReturn(Optional.of(existing));

        // when
        MedicineScheduleChangeResponse response = medicineScheduleService.deleteSchedule(scheduleId, memberId);

        // then
        assertThat(existing.getEffectiveTo()).isEqualTo(today());
        assertThat(existing.isEffectiveOn(today())).isTrue();
        assertThat(existing.isEffectiveOn(today().plusDays(1))).isFalse();
        assertThat(response.scheduleRevision()).isEqualTo(targetMember.getMedicationAlarmRevision()).isEqualTo(1);
        assertThat(response.effectiveFromDate()).isEqualTo(today().plusDays(1));
        then(fcmService).should().sendMessageToUser(
                org.mockito.ArgumentMatchers.eq(memberId), any(FcmSendDto.class));
    }

    @Test
    @DisplayName("스케줄을 생성하면 내일부터 적용하고 현재 revision을 반환한다")
    void 스케줄을_생성하면_내일부터_적용하고_현재_revision을_반환한다() {
        // given
        Long memberId = 1L;
        Member targetMember = seniorWithId(memberId);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));
        given(medicineRepository.findByItemName("타이레놀")).willReturn(Optional.of(mock(Medicine.class)));
        given(medicineScheduleRepository.save(any(MedicineSchedule.class))).willAnswer(invocation -> {
            MedicineSchedule schedule = invocation.getArgument(0);
            ReflectionTestUtils.setField(schedule, "id", 1L);
            return schedule;
        });

        // when
        MedicineScheduleIdResponse response = medicineScheduleService.createSchedule(
                createRequest("08:00"), memberId);

        // then
        ArgumentCaptor<MedicineSchedule> scheduleCaptor = ArgumentCaptor.forClass(MedicineSchedule.class);
        then(medicineScheduleRepository).should().save(scheduleCaptor.capture());
        assertThat(scheduleCaptor.getValue().isEffectiveOn(today())).isFalse();
        assertThat(scheduleCaptor.getValue().isEffectiveOn(today().plusDays(1))).isTrue();
        assertThat(response.medicineScheduleId()).isEqualTo(1L);
        assertThat(response.scheduleRevision()).isEqualTo(targetMember.getMedicationAlarmRevision()).isEqualTo(1);
        assertThat(response.effectiveFromDate()).isEqualTo(today().plusDays(1));
        then(fcmService).should().sendMessageToUser(
                org.mockito.ArgumentMatchers.eq(memberId), any(FcmSendDto.class));
    }

    @Test
    @DisplayName("내일 시작하는 스케줄을 다시 수정하면 같은 버전에만 반영한다")
    void 내일_시작하는_스케줄을_다시_수정하면_같은_버전에만_반영한다() {
        // given
        Long memberId = 1L;
        Long scheduleId = 100L;
        Member targetMember = seniorWithId(memberId);
        MedicineSchedule pending = scheduleEffectiveFrom(
                scheduleId, targetMember, LocalTime.of(8, 0), today().plusDays(1));
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE))
                .willReturn(Optional.of(pending));
        given(medicineRepository.findByItemName("타이레놀")).willReturn(Optional.of(mock(Medicine.class)));

        // when
        MedicineScheduleIdResponse response = medicineScheduleService.updateSchedule(
                scheduleId, updateRequest("09:00"), memberId);

        // then
        assertThat(pending.getEffectiveFrom()).isEqualTo(today().plusDays(1));
        assertThat(pending.getEffectiveTo()).isNull();
        assertThat(pending.getAlarmTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(response.medicineScheduleId()).isEqualTo(scheduleId);
        assertThat(response.scheduleRevision()).isEqualTo(targetMember.getMedicationAlarmRevision()).isEqualTo(1);
        then(medicineScheduleRepository).should(never()).save(any(MedicineSchedule.class));
    }

    @Test
    @DisplayName("내일 시작하는 스케줄을 삭제하면 오늘과 내일 모두에 노출하지 않는다")
    void 내일_시작하는_스케줄을_삭제하면_오늘과_내일_모두에_노출하지_않는다() {
        // given
        Long memberId = 1L;
        Long scheduleId = 100L;
        Member targetMember = seniorWithId(memberId);
        MedicineSchedule pending = scheduleEffectiveFrom(
                scheduleId, targetMember, LocalTime.of(8, 0), today().plusDays(1));
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));
        given(medicineScheduleRepository.findById(scheduleId)).willReturn(Optional.of(pending));

        // when
        MedicineScheduleChangeResponse response = medicineScheduleService.deleteSchedule(scheduleId, memberId);

        // then
        assertThat(pending.getEffectiveFrom()).isEqualTo(today().plusDays(1));
        assertThat(pending.getEffectiveTo()).isEqualTo(today());
        assertThat(pending.isEffectiveOn(today())).isFalse();
        assertThat(pending.isEffectiveOn(today().plusDays(1))).isFalse();
        assertThat(response.scheduleRevision()).isEqualTo(targetMember.getMedicationAlarmRevision()).isEqualTo(1);
    }

    @Test
    @DisplayName("복약 홈을 조회하면 오늘 유효한 버전만 반환한다")
    void 복약_홈을_조회하면_오늘_유효한_버전만_반환한다() {
        // given
        Long memberId = 1L;
        Member targetMember = seniorWithId(memberId);
        MedicineSchedule todaySchedule = scheduleEffectiveFrom(
                100L, targetMember, LocalTime.of(8, 0), today().minusDays(3));
        todaySchedule.closeAsOf(today());
        given(memberRepository.findById(memberId)).willReturn(Optional.of(targetMember));
        given(medicineScheduleRepository.findEffectiveByMemberAndDateWithDetails(
                targetMember, Status.ACTIVE, today())).willReturn(List.of(todaySchedule));

        // when
        MedicineHomeResponse response = medicineScheduleService.getHomeSchedules(memberId);

        // then
        assertThat(response.medicineSchedules()).extracting(MedicineHomeResponse.ScheduleItem::medicineScheduleId)
                .containsExactly(100L);
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
