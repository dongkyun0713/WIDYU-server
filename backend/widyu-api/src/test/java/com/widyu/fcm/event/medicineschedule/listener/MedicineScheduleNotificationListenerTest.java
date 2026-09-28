package com.widyu.fcm.event.medicineschedule.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;

import com.widyu.fcm.application.FcmService;
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
    @DisplayName("복약 알림은 오늘 유효한 스케줄만 조회한다")
    void 복약_알림은_오늘_유효한_스케줄만_조회한다() {
        // given
        given(medicineScheduleRepository.findByAlarmTimeAndStatusEffectiveOn(any(), eq(Status.ACTIVE), any()))
                .willReturn(List.of());

        // when
        listener.checkMedicineSchedules();

        // then: 오늘 날짜 기준 유효한 스케줄만 조회한다
        then(medicineScheduleRepository).should(atLeastOnce())
                .findByAlarmTimeAndStatusEffectiveOn(any(), eq(Status.ACTIVE), eq(LocalDate.now()));
    }

    @Test
    @DisplayName("복용 알림과 보호자 미복용 알림에 알람 스케줄 주인의 revision을 data로 싣는다")
    void 복용_알림과_보호자_알림에_스케줄_주인의_revision을_싣는다() {
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

        // 네 번의 조회(정시·10분·20분·30분)가 모두 같은 미인증 스케줄을 돌려준다
        given(medicineScheduleRepository.findByAlarmTimeAndStatusEffectiveOn(any(), eq(Status.ACTIVE), any()))
                .willReturn(List.of(schedule));
        given(medicationProofRepository.findVerifiedScheduleIds(any(), any(), any())).willReturn(List.of());
        given(familyMembershipRepository.findAllByFamilyIdWithGuardian(100L))
                .willReturn(List.of(FamilyMembership.createMembership(family, guardian)));

        // when
        listener.checkMedicineSchedules();

        // then
        ArgumentCaptor<FcmSendDto> seniorCaptor = ArgumentCaptor.forClass(FcmSendDto.class);
        then(fcmService).should(times(3)).sendMessageToUser(eq(1L), seniorCaptor.capture());
        assertThat(seniorCaptor.getAllValues()).allSatisfy(dto -> assertThat(dto.data())
                .containsEntry("type", "MEDICATION_SCHEDULE_CHANGED")
                .containsEntry("revision", "42"));

        ArgumentCaptor<FcmSendDto> guardianCaptor = ArgumentCaptor.forClass(FcmSendDto.class);
        then(fcmService).should().sendMessageToUser(eq(2L), guardianCaptor.capture());
        assertThat(guardianCaptor.getValue().data())
                .containsEntry("type", "MEDICATION_SCHEDULE_CHANGED")
                .containsEntry("revision", "42");
        assertThat(guardianCaptor.getValue().relatedMemberId()).isEqualTo(1L);
    }
}
