package com.widyu.fcm.event.medicineschedule.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;

import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.NotificationType;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.global.entity.Status;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.goal.medicineschedule.repository.MedicineScheduleRepository;
import com.widyu.medicine.MedicineSchedule;
import com.widyu.member.Family;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyMembershipRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("MedicineScheduleNotificationListener 단위 테스트")
class MedicineScheduleNotificationListenerTest {

    @Mock private FcmService fcmService;
    @Mock private MedicineScheduleRepository medicineScheduleRepository;
    @Mock private MedicationProofRepository medicationProofRepository;
    @Mock private FamilyMembershipRepository familyMembershipRepository;

    @InjectMocks private MedicineScheduleNotificationListener listener;

    @Test
    @DisplayName("복약 후속 알림을 확인하면 정시 서버 푸시 없이 세 시점만 조회한다")
    void 복약_후속_알림을_확인하면_정시_서버_푸시_없이_세_시점만_조회한다() {
        // given
        given(medicineScheduleRepository.findByAlarmTimeAndStatusEffectiveOn(any(), eq(Status.ACTIVE), any()))
                .willReturn(List.of());
        LocalDateTime now = LocalDateTime.of(2026, 10, 2, 8, 0, 3);

        // when
        listener.checkMedicineSchedulesAt(now);

        // then
        then(medicineScheduleRepository).should(times(3))
                .findByAlarmTimeAndStatusEffectiveOn(any(), eq(Status.ACTIVE), eq(now.toLocalDate()));
        then(medicineScheduleRepository).should(never())
                .findByAlarmTimeAndStatusEffectiveOn(eq(LocalTime.of(8, 0)), eq(Status.ACTIVE), any());
        then(fcmService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("복약 인증이 없으면 시니어 푸시 두 건과 보호자 복약 확인 한 건을 만든다")
    void 복약_인증이_없으면_시니어_푸시_두_건과_보호자_복약_확인_한_건을_만든다() {
        // given
        Member senior = Member.createMember(MemberType.SENIOR, "김할머니", "01011112222");
        ReflectionTestUtils.setField(senior, "id", 1L);
        ReflectionTestUtils.setField(senior, "medicationAlarmRevision", 42L);
        Family family = Family.createFamily("ABCDEF");
        ReflectionTestUtils.setField(family, "id", 100L);
        ReflectionTestUtils.setField(senior, "seniorProfile",
                SeniorProfile.createSeniorProfile(senior, family, "서울", "INVITE1", LocalDate.of(1950, 1, 1)));
        Member guardian = Member.createMember(MemberType.GUARDIAN, "김보호", "01033334444");
        ReflectionTestUtils.setField(guardian, "id", 2L);
        ReflectionTestUtils.setField(guardian, "medicationAlarmRevision", 7L);
        MedicineSchedule schedule = MedicineSchedule.create(senior, LocalTime.of(8, 0));
        ReflectionTestUtils.setField(schedule, "id", 15L);

        // 세 번의 조회(+10분·+20분·+30분)가 모두 같은 미인증 스케줄을 돌려준다
        given(medicineScheduleRepository.findByAlarmTimeAndStatusEffectiveOn(any(), eq(Status.ACTIVE), any()))
                .willReturn(List.of(schedule));
        given(medicationProofRepository.findVerifiedScheduleIds(any(), any(), any())).willReturn(List.of());
        given(familyMembershipRepository.findAllByFamilyIdWithGuardian(100L))
                .willReturn(List.of(FamilyMembership.createMembership(family, guardian)));

        // when
        listener.checkMedicineSchedulesAt(LocalDateTime.of(2026, 10, 2, 8, 30));

        // then
        ArgumentCaptor<FcmSendDto> seniorCaptor = ArgumentCaptor.forClass(FcmSendDto.class);
        then(fcmService).should(times(2)).sendMessageToUser(eq(1L), seniorCaptor.capture());
        assertThat(seniorCaptor.getAllValues()).allSatisfy(dto -> assertThat(dto.data())
                .containsEntry("type", dto.notificationType().name())
                .containsEntry("revision", "42"));
        assertThat(seniorCaptor.getAllValues()).extracting(FcmSendDto::notificationType)
                .containsExactly(NotificationType.MEDICATION_REMINDER_10, NotificationType.MEDICATION_REMINDER_20);
        assertThat(seniorCaptor.getAllValues()).extracting(FcmSendDto::title)
                .containsExactly("약 복용 인증이 아직 안 됐어요.", "약 복용 인증 시간이 10분 남았어요.");

        ArgumentCaptor<FcmSendDto> guardianCaptor = ArgumentCaptor.forClass(FcmSendDto.class);
        then(fcmService).should().sendMessageToUser(eq(2L), guardianCaptor.capture());
        assertThat(guardianCaptor.getValue().data())
                .containsEntry("type", "MEDICATION_PROOF_MISSING")
                .containsEntry("revision", "7");
        assertThat(guardianCaptor.getValue().relatedMemberId()).isEqualTo(1L);
        assertThat(guardianCaptor.getValue().seniorId()).isEqualTo(1L);
        assertThat(guardianCaptor.getValue().notificationType())
                .isEqualTo(NotificationType.MEDICATION_PROOF_MISSING);
        assertThat(guardianCaptor.getValue().title())
                .isEqualTo("김할머니 님의 약 복용 인증이 아직 확인되지 않았어요.");
        assertThat(guardianCaptor.getValue().dataForEnqueue(guardianCaptor.getValue().eventId()))
                .containsEntry("deepLink", "widyu-care://seniors/1/medication");
    }

    @Test
    @DisplayName("복약 인증을 완료하면 후속 알림을 보내지 않는다")
    void 복약_인증을_완료하면_후속_알림을_보내지_않는다() {
        // given
        Member senior = Member.createMember(MemberType.SENIOR, "시니어", "01011112222");
        ReflectionTestUtils.setField(senior, "id", 1L);
        MedicineSchedule schedule = MedicineSchedule.create(senior, LocalTime.of(8, 0));
        ReflectionTestUtils.setField(schedule, "id", 15L);
        given(medicineScheduleRepository.findByAlarmTimeAndStatusEffectiveOn(any(), eq(Status.ACTIVE), any()))
                .willReturn(List.of(schedule));
        given(medicationProofRepository.findVerifiedScheduleIds(any(), any(), any()))
                .willReturn(List.of(15L));

        // when
        listener.checkMedicineSchedulesAt(LocalDateTime.of(2026, 10, 2, 8, 30));

        // then
        then(fcmService).shouldHaveNoInteractions();
    }
}
