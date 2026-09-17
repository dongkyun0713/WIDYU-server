package com.widyu.goal.medicineschedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.widyu.global.util.MemberUtil;
import com.widyu.goal.medicineschedule.dto.response.MedicationAlarmSyncResponse;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.goal.medicineschedule.repository.MedicineScheduleRepository;
import com.widyu.member.Member;
import com.widyu.medicine.Medicine;
import com.widyu.medicine.MedicineCategory;
import com.widyu.medicine.MedicineSchedule;
import com.widyu.medicine.MedicineScheduleDetail;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("MedicationAlarmSyncService 단위 테스트")
class MedicationAlarmSyncServiceTest {

    @Mock private MemberUtil memberUtil;
    @Mock private MedicineScheduleRepository medicineScheduleRepository;
    @Mock private MedicationProofRepository medicationProofRepository;

    @InjectMocks private MedicationAlarmSyncService medicationAlarmSyncService;

    @Test
    @DisplayName("오늘 스케줄과 완료 키를 revision 및 Asia/Seoul 기준으로 반환한다")
    void 오늘_스케줄과_완료_키를_반환한다() {
        // given
        Member member = org.mockito.Mockito.mock(Member.class);
        MedicineSchedule schedule = MedicineSchedule.create(member, LocalTime.of(19, 27));
        MedicineCategory category = MedicineCategory.create("저녁약");
        category.addMedicine(MedicineScheduleDetail.create(org.mockito.Mockito.mock(Medicine.class), 2));
        schedule.addCategory(category);
        ReflectionTestUtils.setField(schedule, "id", 15L);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        given(memberUtil.getCurrentMember()).willReturn(member);
        given(member.getId()).willReturn(1L);
        given(member.getMedicationAlarmRevision()).willReturn(42L);
        given(medicineScheduleRepository.findEffectiveByMemberAndDateWithDetails(any(), any(), any()))
                .willReturn(List.of(schedule));
        given(medicationProofRepository.findVerifiedScheduleIdsByMemberAndDate(any(), any(), any()))
                .willReturn(List.of(15L));

        // when
        MedicationAlarmSyncResponse response = medicationAlarmSyncService.getCurrentMemberSnapshot();

        // then
        assertThat(response.revision()).isEqualTo(42L);
        assertThat(response.timeZone()).isEqualTo("Asia/Seoul");
        assertThat(response.schedules()).containsExactly(new MedicationAlarmSyncResponse.Schedule(15L, "19:27", 2));
        assertThat(response.completed()).containsExactly("15:" + today);
    }
}
