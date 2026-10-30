package com.widyu.goal.medicineschedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.util.MemberUtil;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.NotificationType;
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
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
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
        return TODAY;
    }

    // 서비스 시각을 정오로 고정해 실행 시각과 무관하게 알람이 끝났는지 판정한다.
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    // 정오 기준으로 인증 가능 시간(알람 ±30분)이 남은 "아직 안 끝난 알람"이다.
    private static final LocalTime OPEN_ALARM = LocalTime.of(23, 59);
    // 정오 기준으로 인증 가능 시간이 끝난 알람이다.
    private static final LocalTime CLOSED_ALARM = LocalTime.of(0, 0);

    @BeforeEach
    void fixClock() {
        ReflectionTestUtils.setField(medicineScheduleService, "clock",
                Clock.fixed(TODAY.atTime(12, 0).atZone(SEOUL).toInstant(), SEOUL));
    }

    private void givenSavedScheduleId(Long id) {
        given(medicineScheduleRepository.save(any(MedicineSchedule.class))).willAnswer(invocation -> {
            MedicineSchedule schedule = invocation.getArgument(0);
            ReflectionTestUtils.setField(schedule, "id", id);
            return schedule;
        });
    }

    private void givenProvedToday(MedicineSchedule schedule, boolean proved) {
        given(medicationProofRepository.existsByMedicineScheduleAndVerifiedDate(schedule, today()))
                .willReturn(proved);
    }

    @Test
    @DisplayName("아직 안 끝난 알람을 수정하면 어제까지 마감하고 오늘부터 새 버전을 적용한다")
    void 아직_안_끝난_알람을_수정하면_어제까지_마감하고_오늘부터_새_버전을_적용한다() {
        // given
        Long memberId = 1L;
        Long scheduleId = 100L;
        Member targetMember = seniorWithId(memberId);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));
        MedicineSchedule existing = scheduleEffectiveFrom(scheduleId, targetMember, OPEN_ALARM, today().minusDays(5));
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE))
                .willReturn(Optional.of(existing));
        given(medicineRepository.findByItemName("타이레놀")).willReturn(Optional.of(mock(Medicine.class)));
        givenProvedToday(existing, false);
        givenSavedScheduleId(101L);

        // when
        MedicineScheduleIdResponse response = medicineScheduleService.updateSchedule(
                scheduleId, updateRequest("09:00"), memberId);

        // then
        assertThat(existing.getEffectiveTo()).isEqualTo(today().minusDays(1));
        assertThat(existing.isEffectiveOn(today())).isFalse();
        ArgumentCaptor<MedicineSchedule> scheduleCaptor = ArgumentCaptor.forClass(MedicineSchedule.class);
        then(medicineScheduleRepository).should().save(scheduleCaptor.capture());
        assertThat(scheduleCaptor.getValue().getEffectiveFrom()).isEqualTo(today());
        assertThat(scheduleCaptor.getValue().getAlarmTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(response.medicineScheduleId()).isEqualTo(101L);
        assertThat(response.scheduleRevision()).isEqualTo(targetMember.getMedicationAlarmRevision()).isEqualTo(1);
        assertThat(response.effectiveFromDate()).isEqualTo(today());
        ArgumentCaptor<FcmSendDto> messageCaptor = ArgumentCaptor.forClass(FcmSendDto.class);
        then(fcmService).should(times(2)).sendMessageToUser(
                org.mockito.ArgumentMatchers.eq(memberId), messageCaptor.capture());
        assertThat(messageCaptor.getAllValues().getFirst().data())
                .containsEntry("notificationType", "MEDICATION_SCHEDULE_CHANGED")
                .doesNotContainKey("type")
                .containsKey("revision");
        assertThat(messageCaptor.getAllValues().getFirst().content())
                .isEqualTo("바뀐 약 알람은 오늘부터 적용돼요.");
        assertThat(messageCaptor.getAllValues().get(1).notificationType())
                .isEqualTo(NotificationType.MEDICATION_SCHEDULE_SYNC);
    }

    @Test
    @DisplayName("오늘 인증한 알람을 수정하면 오늘 버전과 인증을 그대로 두고 내일부터 새 버전을 적용한다")
    void 오늘_인증한_알람을_수정하면_오늘_버전과_인증을_두고_내일부터_새_버전을_적용한다() {
        // given
        Long memberId = 1L;
        Long scheduleId = 100L;
        Member targetMember = seniorWithId(memberId);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));
        MedicineSchedule existing = scheduleEffectiveFrom(scheduleId, targetMember, OPEN_ALARM, today().minusDays(5));
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE))
                .willReturn(Optional.of(existing));
        given(medicineRepository.findByItemName("타이레놀")).willReturn(Optional.of(mock(Medicine.class)));
        givenProvedToday(existing, true);
        givenSavedScheduleId(101L);
        MedicationProof proof = MedicationProof.create(existing, targetMember, List.of());

        // when
        MedicineScheduleIdResponse response = medicineScheduleService.updateSchedule(
                scheduleId, updateRequest("12:00"), memberId);

        // then
        assertThat(existing.getEffectiveTo()).isEqualTo(today());
        assertThat(existing.isEffectiveOn(today())).isTrue();
        assertThat(existing.getAlarmTime()).isEqualTo(OPEN_ALARM);
        assertThat(proof.getMedicineSchedule()).isSameAs(existing);
        ArgumentCaptor<MedicineSchedule> scheduleCaptor = ArgumentCaptor.forClass(MedicineSchedule.class);
        then(medicineScheduleRepository).should().save(scheduleCaptor.capture());
        assertThat(scheduleCaptor.getValue().getEffectiveFrom()).isEqualTo(today().plusDays(1));
        assertThat(scheduleCaptor.getValue().getAlarmTime()).isEqualTo(LocalTime.of(12, 0));
        assertThat(response.medicineScheduleId()).isEqualTo(101L);
        assertThat(response.effectiveFromDate()).isEqualTo(today().plusDays(1));
        ArgumentCaptor<FcmSendDto> messageCaptor = ArgumentCaptor.forClass(FcmSendDto.class);
        then(fcmService).should(times(2)).sendMessageToUser(
                org.mockito.ArgumentMatchers.eq(memberId), messageCaptor.capture());
        assertThat(messageCaptor.getAllValues().getFirst().content())
                .isEqualTo("바뀐 약 알람은 내일부터 적용돼요.");
    }

    @Test
    @DisplayName("인증 가능 시간이 지난 미인증 알람을 수정하면 오늘은 미인증으로 두고 내일부터 새 버전을 적용한다")
    void 인증_가능_시간이_지난_미인증_알람을_수정하면_내일부터_새_버전을_적용한다() {
        // given
        Long memberId = 1L;
        Long scheduleId = 100L;
        Member targetMember = seniorWithId(memberId);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));
        MedicineSchedule existing = scheduleEffectiveFrom(scheduleId, targetMember, CLOSED_ALARM, today().minusDays(5));
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE))
                .willReturn(Optional.of(existing));
        given(medicineRepository.findByItemName("타이레놀")).willReturn(Optional.of(mock(Medicine.class)));
        givenSavedScheduleId(101L);

        // when
        MedicineScheduleIdResponse response = medicineScheduleService.updateSchedule(
                scheduleId, updateRequest("23:00"), memberId);

        // then
        assertThat(existing.getEffectiveTo()).isEqualTo(today());
        assertThat(existing.isEffectiveOn(today())).isTrue();
        assertThat(response.effectiveFromDate()).isEqualTo(today().plusDays(1));
    }

    @Test
    @DisplayName("오늘 시작한 아직 안 끝난 알람을 수정하면 새 버전 없이 같은 버전을 갱신한다")
    void 오늘_시작한_아직_안_끝난_알람을_수정하면_같은_버전을_갱신한다() {
        // given
        Long memberId = 1L;
        Long scheduleId = 100L;
        Member targetMember = seniorWithId(memberId);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));
        MedicineSchedule existing = scheduleEffectiveFrom(scheduleId, targetMember, OPEN_ALARM, today());
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE))
                .willReturn(Optional.of(existing));
        given(medicineRepository.findByItemName("타이레놀")).willReturn(Optional.of(mock(Medicine.class)));
        givenProvedToday(existing, false);

        // when
        MedicineScheduleIdResponse response = medicineScheduleService.updateSchedule(
                scheduleId, updateRequest("09:00"), memberId);

        // then
        assertThat(existing.getEffectiveTo()).isNull();
        assertThat(existing.getAlarmTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(response.medicineScheduleId()).isEqualTo(scheduleId);
        assertThat(response.effectiveFromDate()).isEqualTo(today());
        then(medicineScheduleRepository).should(never()).save(any(MedicineSchedule.class));
    }

    @Test
    @DisplayName("오늘 시작해 이미 인증한 알람을 수정하면 오늘 버전을 오늘까지 두고 내일 버전을 만든다")
    void 오늘_시작해_이미_인증한_알람을_수정하면_내일_버전을_만든다() {
        // given
        Long memberId = 1L;
        Long scheduleId = 100L;
        Member targetMember = seniorWithId(memberId);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));
        MedicineSchedule existing = scheduleEffectiveFrom(scheduleId, targetMember, OPEN_ALARM, today());
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE))
                .willReturn(Optional.of(existing));
        given(medicineRepository.findByItemName("타이레놀")).willReturn(Optional.of(mock(Medicine.class)));
        givenProvedToday(existing, true);
        givenSavedScheduleId(101L);

        // when
        MedicineScheduleIdResponse response = medicineScheduleService.updateSchedule(
                scheduleId, updateRequest("12:00"), memberId);

        // then
        assertThat(existing.getEffectiveTo()).isEqualTo(today());
        assertThat(existing.getAlarmTime()).isEqualTo(OPEN_ALARM);
        assertThat(response.medicineScheduleId()).isEqualTo(101L);
        assertThat(response.effectiveFromDate()).isEqualTo(today().plusDays(1));
    }

    @Test
    @DisplayName("아직 안 끝난 알람을 삭제하면 오늘부터 중단한다")
    void 아직_안_끝난_알람을_삭제하면_오늘부터_중단한다() {
        // given
        Long memberId = 1L;
        Long scheduleId = 100L;
        Member targetMember = seniorWithId(memberId);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));
        MedicineSchedule existing = scheduleEffectiveFrom(scheduleId, targetMember, OPEN_ALARM, today().minusDays(3));
        given(medicineScheduleRepository.findById(scheduleId)).willReturn(Optional.of(existing));
        givenProvedToday(existing, false);

        // when
        MedicineScheduleChangeResponse response = medicineScheduleService.deleteSchedule(scheduleId, memberId);

        // then
        assertThat(existing.getEffectiveTo()).isEqualTo(today().minusDays(1));
        assertThat(existing.isEffectiveOn(today())).isFalse();
        assertThat(response.scheduleRevision()).isEqualTo(targetMember.getMedicationAlarmRevision()).isEqualTo(1);
        assertThat(response.effectiveFromDate()).isEqualTo(today());
        ArgumentCaptor<FcmSendDto> messages = ArgumentCaptor.forClass(FcmSendDto.class);
        then(fcmService).should(times(2)).sendMessageToUser(
                org.mockito.ArgumentMatchers.eq(memberId), messages.capture());
        assertThat(messages.getAllValues().getFirst().notificationType())
                .isEqualTo(NotificationType.MEDICATION_SCHEDULE_DELETED);
        assertThat(messages.getAllValues().getFirst().content())
                .isEqualTo("오늘부터 이 알람은 울리지 않아요.");
        assertThat(messages.getAllValues().get(1).notificationType())
                .isEqualTo(NotificationType.MEDICATION_SCHEDULE_SYNC);
    }

    @Test
    @DisplayName("오늘 인증한 알람을 삭제하면 오늘 기록을 두고 내일부터 중단한다")
    void 오늘_인증한_알람을_삭제하면_오늘_기록을_두고_내일부터_중단한다() {
        // given
        Long memberId = 1L;
        Long scheduleId = 100L;
        Member targetMember = seniorWithId(memberId);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));
        MedicineSchedule existing = scheduleEffectiveFrom(scheduleId, targetMember, OPEN_ALARM, today().minusDays(3));
        given(medicineScheduleRepository.findById(scheduleId)).willReturn(Optional.of(existing));
        givenProvedToday(existing, true);

        // when
        MedicineScheduleChangeResponse response = medicineScheduleService.deleteSchedule(scheduleId, memberId);

        // then
        assertThat(existing.getEffectiveTo()).isEqualTo(today());
        assertThat(existing.isEffectiveOn(today())).isTrue();
        assertThat(existing.isEffectiveOn(today().plusDays(1))).isFalse();
        assertThat(response.effectiveFromDate()).isEqualTo(today().plusDays(1));
    }

    @Test
    @DisplayName("인증 가능 시간이 지난 미인증 알람을 삭제하면 오늘은 미인증으로 두고 내일부터 중단한다")
    void 인증_가능_시간이_지난_미인증_알람을_삭제하면_내일부터_중단한다() {
        // given
        Long memberId = 1L;
        Long scheduleId = 100L;
        Member targetMember = seniorWithId(memberId);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));
        MedicineSchedule existing = scheduleEffectiveFrom(scheduleId, targetMember, CLOSED_ALARM, today().minusDays(3));
        given(medicineScheduleRepository.findById(scheduleId)).willReturn(Optional.of(existing));

        // when
        MedicineScheduleChangeResponse response = medicineScheduleService.deleteSchedule(scheduleId, memberId);

        // then
        assertThat(existing.getEffectiveTo()).isEqualTo(today());
        assertThat(response.effectiveFromDate()).isEqualTo(today().plusDays(1));
    }

    @Test
    @DisplayName("아직 인증 가능 시간이 남은 시각으로 스케줄을 생성하면 오늘부터 적용하고 현재 revision을 반환한다")
    void 아직_인증_가능한_시각으로_스케줄을_생성하면_오늘부터_적용한다() {
        // given
        Long memberId = 1L;
        Member targetMember = seniorWithId(memberId);
        Member actor = Member.createMember(MemberType.GUARDIAN, "방장", "01099998888");
        given(memberUtil.getCurrentMember()).willReturn(actor);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));
        given(medicineRepository.findByItemName("타이레놀")).willReturn(Optional.of(mock(Medicine.class)));
        givenSavedScheduleId(1L);

        // when
        MedicineScheduleIdResponse response = medicineScheduleService.createSchedule(
                createRequest("23:59"), memberId);

        // then
        ArgumentCaptor<MedicineSchedule> scheduleCaptor = ArgumentCaptor.forClass(MedicineSchedule.class);
        then(medicineScheduleRepository).should().save(scheduleCaptor.capture());
        assertThat(scheduleCaptor.getValue().isEffectiveOn(today())).isTrue();
        assertThat(response.medicineScheduleId()).isEqualTo(1L);
        assertThat(response.scheduleRevision()).isEqualTo(targetMember.getMedicationAlarmRevision()).isEqualTo(1);
        assertThat(response.effectiveFromDate()).isEqualTo(today());
        ArgumentCaptor<FcmSendDto> messages = ArgumentCaptor.forClass(FcmSendDto.class);
        then(fcmService).should(times(2)).sendMessageToUser(
                org.mockito.ArgumentMatchers.eq(memberId), messages.capture());
        assertThat(messages.getAllValues().getFirst().notificationType())
                .isEqualTo(NotificationType.MEDICATION_SCHEDULE_CREATED);
        assertThat(messages.getAllValues().getFirst().title())
                .isEqualTo("방장 님이 새 약 알람을 등록했어요.");
        assertThat(messages.getAllValues().getFirst().content())
                .isEqualTo("오늘부터 알람이 울려요.");
        assertThat(messages.getAllValues().getFirst().actorDisplayName()).isEqualTo("방장");
        assertThat(messages.getAllValues().getFirst().effectiveFromDate())
                .isEqualTo(today().toString());
        assertThat(messages.getAllValues().get(1).notificationType())
                .isEqualTo(NotificationType.MEDICATION_SCHEDULE_SYNC);
    }

    @Test
    @DisplayName("인증 가능 시간이 이미 지난 시각으로 스케줄을 생성하면 내일부터 적용한다")
    void 인증_가능_시간이_지난_시각으로_스케줄을_생성하면_내일부터_적용한다() {
        // given
        Long memberId = 1L;
        Member targetMember = seniorWithId(memberId);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));
        given(medicineRepository.findByItemName("타이레놀")).willReturn(Optional.of(mock(Medicine.class)));
        givenSavedScheduleId(1L);

        // when
        MedicineScheduleIdResponse response = medicineScheduleService.createSchedule(
                createRequest("00:00"), memberId);

        // then
        ArgumentCaptor<MedicineSchedule> scheduleCaptor = ArgumentCaptor.forClass(MedicineSchedule.class);
        then(medicineScheduleRepository).should().save(scheduleCaptor.capture());
        assertThat(scheduleCaptor.getValue().isEffectiveOn(today())).isFalse();
        assertThat(scheduleCaptor.getValue().isEffectiveOn(today().plusDays(1))).isTrue();
        assertThat(response.effectiveFromDate()).isEqualTo(today().plusDays(1));
    }

    @Test
    @DisplayName("현재 알람이 5개면 새 알람을 생성한다")
    void 현재_알람이_5개면_새_알람을_생성한다() {
        // given
        Long memberId = 1L;
        Member targetMember = seniorWithId(memberId);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));
        given(medicineScheduleRepository.countByMemberAndStatusAndEffectiveToIsNull(targetMember, Status.ACTIVE))
                .willReturn(5L);
        given(medicineRepository.findByItemName("타이레놀")).willReturn(Optional.of(mock(Medicine.class)));
        givenSavedScheduleId(6L);

        // when
        MedicineScheduleIdResponse response = medicineScheduleService.createSchedule(
                createRequest("23:59"), memberId);

        // then
        assertThat(response.medicineScheduleId()).isEqualTo(6L);
    }

    @Test
    @DisplayName("현재 알람이 이미 6개면 새 알람 생성을 거부하고 저장·알림을 하지 않는다")
    void 현재_알람이_6개면_새_알람_생성을_거부한다() {
        // given
        Long memberId = 1L;
        Member targetMember = seniorWithId(memberId);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(targetMember));
        given(medicineScheduleRepository.countByMemberAndStatusAndEffectiveToIsNull(targetMember, Status.ACTIVE))
                .willReturn(6L);

        // when & then
        assertThatThrownBy(() -> medicineScheduleService.createSchedule(createRequest("23:59"), memberId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("최대 6개");
        then(medicineScheduleRepository).should(never()).save(any(MedicineSchedule.class));
        then(fcmService).shouldHaveNoInteractions();
        assertThat(targetMember.getMedicationAlarmRevision()).isZero();
    }

    @Test
    @DisplayName("내일 시작하는 스케줄을 수정하면 같은 버전에만 반영하고 시작일을 유지한다")
    void 내일_시작하는_스케줄을_수정하면_같은_버전에만_반영하고_시작일을_유지한다() {
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
        assertThat(response.effectiveFromDate()).isEqualTo(today().plusDays(1));
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
        assertThat(pending.getEffectiveTo()).isEqualTo(today().minusDays(1));
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
                scheduleId, targetMember, LocalTime.of(8, 0), today().minusDays(10));
        ReflectionTestUtils.setField(closed, "effectiveTo", today().minusDays(5));
        given(medicineScheduleRepository.findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE))
                .willReturn(Optional.of(closed));

        // when & then
        assertThatThrownBy(() -> medicineScheduleService.updateSchedule(scheduleId, updateRequest("09:00"), memberId))
                .isInstanceOf(BusinessException.class);
        then(fcmService).shouldHaveNoInteractions();
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
                scheduleId, targetMember, LocalTime.of(8, 0), today().minusDays(10));
        ReflectionTestUtils.setField(closed, "effectiveTo", today().minusDays(5));
        given(medicineScheduleRepository.findById(scheduleId)).willReturn(Optional.of(closed));

        // when & then
        assertThatThrownBy(() -> medicineScheduleService.deleteSchedule(scheduleId, memberId))
                .isInstanceOf(BusinessException.class);
        then(fcmService).shouldHaveNoInteractions();
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
        given(proof.getVerifiedDate()).willReturn(LocalDate.of(2026, 7, 15));

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
        given(proof.getVerifiedDate()).willReturn(LocalDate.of(2026, 7, 15));

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
