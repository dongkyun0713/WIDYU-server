package com.widyu.goal.home.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.widyu.global.entity.Status;
import com.widyu.global.util.MemberUtil;
import com.widyu.goal.DailyGoalStatus;
import com.widyu.goal.healthschedule.repository.HealthScheduleRepository;
import com.widyu.goal.home.dto.response.GuardianGoalStatsResponse;
import com.widyu.goal.home.dto.response.SeniorGoalHomeResponse;
import com.widyu.goal.home.dto.response.SeniorWeeklyGoalStatusResponse;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.goal.medicineschedule.repository.MedicineScheduleRepository;
import com.widyu.goal.walk.repository.WalkRepository;
import com.widyu.member.Member;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import com.widyu.medicine.MedicationProof;
import com.widyu.medicine.MedicineSchedule;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("GoalHomeService 주간 통계 단위 테스트")
class GoalHomeServiceTest {

    @Mock private FamilyMembershipRepository familyMembershipRepository;
    @Mock private SeniorProfileRepository seniorProfileRepository;
    @Mock private MedicineScheduleRepository medicineScheduleRepository;
    @Mock private MedicationProofRepository medicationProofRepository;
    @Mock private WalkRepository walkRepository;
    @Mock private HealthScheduleRepository healthScheduleRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private MemberUtil memberUtil;

    @InjectMocks private GoalHomeService goalHomeService;

    @Test
    @DisplayName("시니어 주간 상태는 기간 데이터를 한 번씩 조회하고 기존 날짜별 상태를 유지한다")
    void 시니어_주간_상태는_기간_데이터를_한_번씩_조회한다() {
        // given
        LocalDate today = LocalDate.now();
        LocalDate startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        LocalDate endOfWeek = startOfWeek.plusDays(6);
        Member member = mock(Member.class);
        MedicineSchedule schedule = mock(MedicineSchedule.class);
        MedicationProof proof = mock(MedicationProof.class);

        given(memberUtil.getCurrentMember()).willReturn(member);
        given(member.getId()).willReturn(11L);
        given(schedule.getId()).willReturn(1L);
        given(schedule.isEffectiveOn(any(LocalDate.class))).willReturn(true);
        given(proof.getMedicineSchedule()).willReturn(schedule);
        given(proof.getVerifiedAt()).willReturn(today.atTime(12, 0));
        given(medicineScheduleRepository.findEffectiveByMemberAndDateRange(
                member, Status.ACTIVE, startOfWeek, endOfWeek)).willReturn(List.of(schedule));
        given(medicationProofRepository.findByMemberIdAndDateRange(
                11L, startOfWeek.atStartOfDay(), endOfWeek.atTime(java.time.LocalTime.MAX)))
                .willReturn(List.of(proof));
        given(walkRepository.findByMemberAndWalkDateBetweenOrderByWalkDateAsc(
                member, startOfWeek, endOfWeek)).willReturn(List.of());

        // when
        SeniorWeeklyGoalStatusResponse response = goalHomeService.getSeniorWeeklyGoalStatus();

        // then
        List<DailyGoalStatus> expected = new ArrayList<>();
        for (LocalDate date = startOfWeek; !date.isAfter(endOfWeek); date = date.plusDays(1)) {
            if (date.isBefore(today)) {
                expected.add(DailyGoalStatus.FAILED);
            } else if (date.isEqual(today)) {
                expected.add(DailyGoalStatus.COMPLETED);
            } else {
                expected.add(DailyGoalStatus.NOT_STARTED);
            }
        }
        assertThat(response.thisWeekGoalRates()).containsExactlyElementsOf(expected);
        verify(medicineScheduleRepository, times(1))
                .findEffectiveByMemberAndDateRange(member, Status.ACTIVE, startOfWeek, endOfWeek);
        verify(medicationProofRepository, times(1))
                .findByMemberIdAndDateRange(11L, startOfWeek.atStartOfDay(), endOfWeek.atTime(java.time.LocalTime.MAX));
        verify(walkRepository, times(1))
                .findByMemberAndWalkDateBetweenOrderByWalkDateAsc(member, startOfWeek, endOfWeek);
    }

    @Test
    @DisplayName("보호자 주간 통계는 14일 데이터를 한 번씩 조회하고 이번 주 일별 결과를 재사용한다")
    void 보호자_주간_통계는_기간_데이터를_한_번씩_조회한다() {
        // given
        LocalDate today = LocalDate.now();
        LocalDate thisWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        LocalDate thisWeekEnd = thisWeekStart.plusDays(6);
        LocalDate lastWeekStart = thisWeekStart.minusWeeks(1);
        Member member = mock(Member.class);
        MedicineSchedule schedule = mock(MedicineSchedule.class);
        MedicationProof proof = mock(MedicationProof.class);

        given(memberRepository.findById(11L)).willReturn(Optional.of(member));
        given(member.getId()).willReturn(11L);
        given(schedule.getId()).willReturn(1L);
        given(schedule.isEffectiveOn(any(LocalDate.class))).willReturn(true);
        given(proof.getMedicineSchedule()).willReturn(schedule);
        given(proof.getVerifiedAt()).willReturn(LocalDateTime.of(today, java.time.LocalTime.NOON));
        given(medicineScheduleRepository.findEffectiveByMemberAndDateRange(
                member, Status.ACTIVE, lastWeekStart, thisWeekEnd)).willReturn(List.of(schedule));
        given(medicationProofRepository.findByMemberIdAndDateRange(
                11L, lastWeekStart.atStartOfDay(), thisWeekEnd.atTime(java.time.LocalTime.MAX)))
                .willReturn(List.of(proof));
        given(walkRepository.findByMemberAndWalkDateBetweenOrderByWalkDateAsc(
                member, lastWeekStart, thisWeekEnd)).willReturn(List.of());

        // when
        GuardianGoalStatsResponse response = goalHomeService.getGuardianGoalStats(11L);

        // then
        List<Double> expectedDailyRates = new ArrayList<>();
        for (LocalDate date = thisWeekStart; !date.isAfter(thisWeekEnd); date = date.plusDays(1)) {
            if (date.isEqual(today)) {
                expectedDailyRates.add(1.0);
            } else {
                expectedDailyRates.add(0.0);
            }
        }
        assertThat(response.lastWeekGoalRate()).isZero();
        assertThat(response.todayGoalRate()).isEqualTo(1.0);
        assertThat(response.thisWeekGoalRates()).containsExactlyElementsOf(expectedDailyRates);
        verify(medicineScheduleRepository, times(1))
                .findEffectiveByMemberAndDateRange(member, Status.ACTIVE, lastWeekStart, thisWeekEnd);
        verify(medicationProofRepository, times(1)).findByMemberIdAndDateRange(
                11L, lastWeekStart.atStartOfDay(), thisWeekEnd.atTime(java.time.LocalTime.MAX));
        verify(walkRepository, times(1))
                .findByMemberAndWalkDateBetweenOrderByWalkDateAsc(member, lastWeekStart, thisWeekEnd);
    }

    @Test
    @DisplayName("주중에 교체된 스케줄은 버전별 유효기간에 따라 날짜별로 다르게 집계된다")
    void 주중에_교체된_스케줄은_버전별_유효기간을_따른다() {
        // given: 어제까지 유효했던 구버전 + 오늘부터 유효한 신버전 (실제 isEffectiveOn 동작 검증)
        LocalDate today = LocalDate.now();
        LocalDate startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        LocalDate endOfWeek = startOfWeek.plusDays(6);
        Member member = mock(Member.class);

        MedicineSchedule closedVersion = schedule(member, 1L, startOfWeek, today.minusDays(1));
        MedicineSchedule currentVersion = schedule(member, 2L, today, null);

        MedicationProof proof = mock(MedicationProof.class);
        given(proof.getMedicineSchedule()).willReturn(currentVersion);
        given(proof.getVerifiedAt()).willReturn(today.atTime(LocalTime.NOON));

        given(memberUtil.getCurrentMember()).willReturn(member);
        given(member.getId()).willReturn(11L);
        given(medicineScheduleRepository.findEffectiveByMemberAndDateRange(
                member, Status.ACTIVE, startOfWeek, endOfWeek))
                .willReturn(List.of(closedVersion, currentVersion));
        given(medicationProofRepository.findByMemberIdAndDateRange(
                11L, startOfWeek.atStartOfDay(), endOfWeek.atTime(LocalTime.MAX)))
                .willReturn(List.of(proof));
        given(walkRepository.findByMemberAndWalkDateBetweenOrderByWalkDateAsc(
                member, startOfWeek, endOfWeek)).willReturn(List.of());

        // when
        SeniorWeeklyGoalStatusResponse response = goalHomeService.getSeniorWeeklyGoalStatus();

        // then: 오늘은 신버전 1개만 목표이므로 COMPLETED (두 버전이 모두 잡히면 IN_PROGRESS가 된다)
        List<DailyGoalStatus> expected = new ArrayList<>();
        for (LocalDate date = startOfWeek; !date.isAfter(endOfWeek); date = date.plusDays(1)) {
            if (date.isBefore(today)) {
                expected.add(DailyGoalStatus.FAILED);
            } else if (date.isEqual(today)) {
                expected.add(DailyGoalStatus.COMPLETED);
            } else {
                expected.add(DailyGoalStatus.NOT_STARTED);
            }
        }
        assertThat(response.thisWeekGoalRates()).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("오늘 남은 알람이 없으면 마지막 알람을 다음 알람으로 반환한다")
    void 오늘_남은_알람이_없으면_마지막_알람을_반환한다() {
        // given
        Member member = mock(Member.class);
        List<MedicineSchedule> schedules = List.of(
                alarmSchedule(member, 1L, LocalTime.of(8, 0)),
                alarmSchedule(member, 2L, LocalTime.of(13, 0)),
                alarmSchedule(member, 3L, LocalTime.of(20, 0)));

        // when
        MedicineSchedule nextSchedule = GoalHomeService.findNextSchedule(schedules, LocalTime.of(23, 0));

        // then
        assertThat(nextSchedule.getId()).isEqualTo(3L);
        assertThat(nextSchedule.getAlarmTime()).isEqualTo(LocalTime.of(20, 0));
    }

    @Test
    @DisplayName("오늘 남은 알람이 있으면 가장 가까운 알람을 다음 알람으로 반환한다")
    void 오늘_남은_알람이_있으면_가장_가까운_알람을_반환한다() {
        // given
        Member member = mock(Member.class);
        List<MedicineSchedule> schedules = List.of(
                alarmSchedule(member, 1L, LocalTime.of(8, 0)),
                alarmSchedule(member, 2L, LocalTime.of(13, 0)),
                alarmSchedule(member, 3L, LocalTime.of(20, 0)));

        // when
        MedicineSchedule nextSchedule = GoalHomeService.findNextSchedule(schedules, LocalTime.of(9, 0));

        // then
        assertThat(nextSchedule.getId()).isEqualTo(2L);
        assertThat(nextSchedule.getAlarmTime()).isEqualTo(LocalTime.of(13, 0));
    }

    @Test
    @DisplayName("한 스케줄에 인증이 여러 건이면 조회 순서와 무관하게 최초 인증 이미지를 반환한다")
    void 중복_인증이면_최초_인증_이미지를_반환한다() {
        // given
        LocalDate today = LocalDate.now();
        Member member = mock(Member.class);
        MedicineSchedule schedule = alarmSchedule(member, 1L, LocalTime.of(8, 0));
        MedicationProof earlier = proof(schedule, member, today.atTime(8, 5), "https://cdn.widyu.shop/earlier.jpg");
        MedicationProof later = proof(schedule, member, today.atTime(8, 25), "https://cdn.widyu.shop/later.jpg");

        given(memberUtil.getCurrentMember()).willReturn(member);
        given(member.getId()).willReturn(11L);
        given(medicineScheduleRepository.findEffectiveByMemberAndDateWithDetails(
                member, Status.ACTIVE, today)).willReturn(List.of(schedule));
        // 조회 쿼리에 정렬이 없으므로 늦은 인증이 먼저 반환되는 순서를 재현한다.
        given(medicationProofRepository.findByMemberIdAndDateRange(
                11L, today.atStartOfDay(), today.atTime(LocalTime.MAX)))
                .willReturn(List.of(later, earlier));
        given(walkRepository.findByMemberAndWalkDate(member, today)).willReturn(Optional.empty());
        given(healthScheduleRepository.findByMemberIdAndWeek(any(), any(), any())).willReturn(List.of());

        // when
        SeniorGoalHomeResponse response = goalHomeService.getSeniorGoalHome();

        // then
        assertThat(response.medicine().proofImageUrl()).isEqualTo("https://cdn.widyu.shop/earlier.jpg");
        assertThat(response.medicine().takenCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("오늘 유효 목록에서 빠진 스케줄의 인증은 복용 횟수에 포함하지 않는다")
    void 유효하지_않은_스케줄의_인증은_복용_횟수에서_제외한다() {
        // given: 오전에 인증한 뒤 스케줄을 수정해 구 버전이 오늘 유효 목록에서 빠진 상황
        LocalDate today = LocalDate.now();
        Member member = mock(Member.class);
        MedicineSchedule closedSchedule = alarmSchedule(member, 1L, LocalTime.of(8, 0));
        MedicineSchedule currentSchedule = alarmSchedule(member, 2L, LocalTime.of(9, 0));
        MedicationProof closedProof = proof(closedSchedule, member, today.atTime(8, 5), "https://cdn.widyu.shop/old.jpg");

        given(memberUtil.getCurrentMember()).willReturn(member);
        given(member.getId()).willReturn(11L);
        given(medicineScheduleRepository.findEffectiveByMemberAndDateWithDetails(
                member, Status.ACTIVE, today)).willReturn(List.of(currentSchedule));
        given(medicationProofRepository.findByMemberIdAndDateRange(
                11L, today.atStartOfDay(), today.atTime(LocalTime.MAX)))
                .willReturn(List.of(closedProof));
        given(walkRepository.findByMemberAndWalkDate(member, today)).willReturn(Optional.empty());
        given(healthScheduleRepository.findByMemberIdAndWeek(any(), any(), any())).willReturn(List.of());

        // when
        SeniorGoalHomeResponse response = goalHomeService.getSeniorGoalHome();

        // then
        assertThat(response.medicine().takenCount()).isZero();
        assertThat(response.medicine().totalCount()).isEqualTo(1);
        assertThat(response.medicine().proofImageUrl()).isNull();
    }

    private MedicationProof proof(MedicineSchedule schedule, Member member, LocalDateTime verifiedAt, String imageUrl) {
        MedicationProof proof = MedicationProof.create(schedule, member, List.of(imageUrl));
        ReflectionTestUtils.setField(proof, "verifiedAt", verifiedAt);
        return proof;
    }

    private MedicineSchedule alarmSchedule(Member member, Long id, LocalTime alarmTime) {
        MedicineSchedule schedule = MedicineSchedule.create(member, alarmTime);
        ReflectionTestUtils.setField(schedule, "id", id);
        return schedule;
    }

    private MedicineSchedule schedule(Member member, Long id, LocalDate effectiveFrom, LocalDate effectiveTo) {
        MedicineSchedule schedule = MedicineSchedule.create(member, LocalTime.of(8, 0));
        ReflectionTestUtils.setField(schedule, "id", id);
        ReflectionTestUtils.setField(schedule, "effectiveFrom", effectiveFrom);
        ReflectionTestUtils.setField(schedule, "effectiveTo", effectiveTo);
        return schedule;
    }
}
