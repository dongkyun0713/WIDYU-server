package com.widyu.goal.healthschedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.then;

import com.widyu.goal.healthschedule.repository.HealthScheduleRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.util.MemberUtil;
import com.widyu.healthschedule.HealthSchedule;
import com.widyu.healthschedule.ProgressStatus;
import com.widyu.location.SeniorLocation;
import com.widyu.location.realtime.repository.SeniorLocationRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.application.SeniorProfileService;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("HealthScheduleProgressService 방문 완료·포인트 적립 단위 테스트")
class HealthScheduleProgressServiceTest {

    @Mock private HealthScheduleRepository healthScheduleRepository;
    @Mock private SeniorLocationRepository seniorLocationRepository;
    @Mock private SeniorProfileRepository seniorProfileRepository;
    @Mock private FamilyMembershipRepository familyMembershipRepository;
    @Mock private SeniorProfileService seniorProfileService;
    @Mock private MemberUtil memberUtil;

    @InjectMocks private HealthScheduleProgressService healthScheduleProgressService;

    @Captor private ArgumentCaptor<LocalDateTime> beforeDateCaptor;

    private HealthSchedule upcomingScheduleAt(LocalDateTime scheduledAt) {
        Member member = Member.createMember(MemberType.SENIOR, "부모님", "01011112222");
        return HealthSchedule.create(member, "건강검진", "서울시", 37.5, 127.0, scheduledAt);
    }

    private HealthSchedule upcomingScheduleFor(Member member, LocalDateTime scheduledAt) {
        return HealthSchedule.create(member, "건강검진", "서울시", 37.5, 127.0, scheduledAt);
    }

    private Member seniorMember() {
        Member senior = Member.createMember(MemberType.SENIOR, "부모님", "01011112222");
        ReflectionTestUtils.setField(senior, "id", 1L);
        return senior;
    }

    private Member guardianMember() {
        Member guardian = Member.createMember(MemberType.GUARDIAN, "자녀", "01033334444");
        ReflectionTestUtils.setField(guardian, "id", 2L);
        return guardian;
    }

    private HealthSchedule scheduleWithId(Member member, LocalDateTime scheduledAt, Long scheduleId) {
        HealthSchedule schedule = upcomingScheduleFor(member, scheduledAt);
        ReflectionTestUtils.setField(schedule, "id", scheduleId);
        return schedule;
    }

    @Test
    @DisplayName("오늘 이전 UPCOMING 일정을 여러 날치 조회해 모두 INCOMPLETE로 마감한다")
    void 오늘_이전_UPCOMING_일정을_모두_INCOMPLETE로_마감한다() {
        // given
        HealthSchedule yesterday = upcomingScheduleAt(LocalDateTime.now().minusDays(1));
        HealthSchedule threeDaysAgo = upcomingScheduleAt(LocalDateTime.now().minusDays(3));
        given(healthScheduleRepository.findByStatusAndScheduledAtBefore(
                eq(ProgressStatus.UPCOMING), beforeDateCaptor.capture()))
                .willReturn(List.of(yesterday, threeDaysAgo));

        // when
        healthScheduleProgressService.markOverdueSchedulesAsIncomplete();

        // then
        assertThat(yesterday.getProgressStatus()).isEqualTo(ProgressStatus.INCOMPLETE);
        assertThat(threeDaysAgo.getProgressStatus()).isEqualTo(ProgressStatus.INCOMPLETE);
        // 마감 기준은 완료 허용창(예정 시각 + 30분)이 지난 시점 = now - 30분
        assertThat(beforeDateCaptor.getValue())
                .isBeforeOrEqualTo(LocalDateTime.now().minusMinutes(HealthSchedule.COMPLETION_GRACE_MINUTES));
    }

    @Test
    @DisplayName("마감할 지난 일정이 없으면 아무 상태도 변경하지 않는다")
    void 마감할_지난_일정이_없으면_변경하지_않는다() {
        // given
        given(healthScheduleRepository.findByStatusAndScheduledAtBefore(
                eq(ProgressStatus.UPCOMING), beforeDateCaptor.capture()))
                .willReturn(List.of());

        // when
        healthScheduleProgressService.markOverdueSchedulesAsIncomplete();

        // then
        then(healthScheduleRepository).should()
                .findByStatusAndScheduledAtBefore(eq(ProgressStatus.UPCOMING), beforeDateCaptor.capture());
    }

    @Test
    @DisplayName("당일 00시부터 일정 시간 30분 후까지는 방문 인증 완료 처리한다")
    void 방문_인증_허용_시간이면_완료_처리한다() {
        // given
        Long scheduleId = 1L;
        Member senior = seniorMember();
        // 완료 허용창 안(예정 시각 직후)이 되도록 과거 5분으로 고정해 자정 경계에서도 안정적으로 통과시킨다.
        HealthSchedule schedule = upcomingScheduleFor(senior, LocalDateTime.now().minusMinutes(5));
        given(memberUtil.getCurrentMember()).willReturn(senior);
        given(healthScheduleRepository.findById(scheduleId)).willReturn(Optional.of(schedule));
        given(seniorLocationRepository.findBySeniorId(senior.getId()))
                .willReturn(Optional.of(SeniorLocation.of(senior.getId(), 37.5, 127.0)));

        // when
        healthScheduleProgressService.completeSchedule(scheduleId);

        // then
        assertThat(schedule.getProgressStatus()).isEqualTo(ProgressStatus.COMPLETED);
    }

    @Test
    @DisplayName("일정 당일 전에는 방문 인증을 완료 처리하지 않는다")
    void 일정_당일_전에는_방문_인증을_완료하지_않는다() {
        // given
        Long scheduleId = 1L;
        Member senior = seniorMember();
        HealthSchedule schedule = upcomingScheduleFor(senior, LocalDateTime.now().plusDays(1));
        given(memberUtil.getCurrentMember()).willReturn(senior);
        given(healthScheduleRepository.findById(scheduleId)).willReturn(Optional.of(schedule));

        // when & then
        assertThatThrownBy(() -> healthScheduleProgressService.completeSchedule(scheduleId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("당일 00시");
        assertThat(schedule.getProgressStatus()).isEqualTo(ProgressStatus.UPCOMING);
    }

    @Test
    @DisplayName("일정 시간 30분 후가 지나면 방문 인증을 완료 처리하지 않는다")
    void 일정시간_30분_후가_지나면_방문_인증을_완료하지_않는다() {
        // given
        Long scheduleId = 1L;
        Member senior = seniorMember();
        HealthSchedule schedule = upcomingScheduleFor(senior, LocalDateTime.now().minusMinutes(31));
        given(memberUtil.getCurrentMember()).willReturn(senior);
        given(healthScheduleRepository.findById(scheduleId)).willReturn(Optional.of(schedule));

        // when & then
        assertThatThrownBy(() -> healthScheduleProgressService.completeSchedule(scheduleId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("30분 후");
        assertThat(schedule.getProgressStatus()).isEqualTo(ProgressStatus.UPCOMING);
    }

    @Test
    @DisplayName("최신 위치가 일정 장소 반경 밖이면 방문 인증을 완료 처리하지 않는다")
    void 최신_위치가_일정장소_반경_밖이면_방문_인증을_완료하지_않는다() {
        // given
        Long scheduleId = 1L;
        Member senior = seniorMember();
        HealthSchedule schedule = upcomingScheduleFor(senior, LocalDateTime.now().minusMinutes(5));
        given(memberUtil.getCurrentMember()).willReturn(senior);
        given(healthScheduleRepository.findById(scheduleId)).willReturn(Optional.of(schedule));
        given(seniorLocationRepository.findBySeniorId(senior.getId()))
                .willReturn(Optional.of(SeniorLocation.of(senior.getId(), 37.0, 127.0)));

        // when & then
        assertThatThrownBy(() -> healthScheduleProgressService.completeSchedule(scheduleId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("75m");
        assertThat(schedule.getProgressStatus()).isEqualTo(ProgressStatus.UPCOMING);
    }

    @Test
    @DisplayName("실시간 위치가 당일 일정 장소 반경 안에 들어오면 자동 완료 처리한다")
    void 실시간_위치가_당일_일정장소_반경_안이면_자동_완료한다() {
        // given
        Member senior = seniorMember();
        HealthSchedule schedule = upcomingScheduleFor(senior, LocalDateTime.now().minusMinutes(5));
        given(healthScheduleRepository.findByMemberIdAndStatusAndDate(
                eq(senior.getId()), eq(ProgressStatus.UPCOMING), beforeDateCaptor.capture(), beforeDateCaptor.capture()))
                .willReturn(List.of(schedule));

        // when
        healthScheduleProgressService.completeArrivedSchedules(senior.getId(), 37.5, 127.0);

        // then
        assertThat(schedule.getProgressStatus()).isEqualTo(ProgressStatus.COMPLETED);
    }

    @Test
    @DisplayName("실시간 위치가 일정 장소 반경 밖이면 자동 완료 처리하지 않는다")
    void 실시간_위치가_일정장소_반경_밖이면_자동_완료하지_않는다() {
        // given
        Member senior = seniorMember();
        HealthSchedule schedule = upcomingScheduleFor(senior, LocalDateTime.now().minusMinutes(5));
        given(healthScheduleRepository.findByMemberIdAndStatusAndDate(
                eq(senior.getId()), eq(ProgressStatus.UPCOMING), beforeDateCaptor.capture(), beforeDateCaptor.capture()))
                .willReturn(List.of(schedule));

        // when
        healthScheduleProgressService.completeArrivedSchedules(senior.getId(), 37.0, 127.0);

        // then
        assertThat(schedule.getProgressStatus()).isEqualTo(ProgressStatus.UPCOMING);
    }

    @Test
    @DisplayName("수동 완료로 최초 방문 인증하면 rewardPoint를 1회 적립한다")
    void 수동_완료로_최초_방문_인증하면_포인트를_1회_적립한다() {
        // given
        Long scheduleId = 1L;
        Member senior = seniorMember();
        HealthSchedule schedule = scheduleWithId(senior, LocalDateTime.now().minusMinutes(5), scheduleId);
        given(memberUtil.getCurrentMember()).willReturn(senior);
        given(healthScheduleRepository.findById(scheduleId)).willReturn(Optional.of(schedule));
        given(seniorLocationRepository.findBySeniorId(senior.getId()))
                .willReturn(Optional.of(SeniorLocation.of(senior.getId(), 37.5, 127.0)));

        // when
        healthScheduleProgressService.completeSchedule(scheduleId);

        // then
        assertThat(schedule.getProgressStatus()).isEqualTo(ProgressStatus.COMPLETED);
        assertThat(schedule.getIsReward()).isTrue();
        then(seniorProfileService).should()
                .addPointsToMember(senior.getId(), 100L, "건강 일정 방문", "HEALTH_SCHEDULE_REWARD:" + scheduleId);
    }

    @Test
    @DisplayName("실시간 위치로 자동 완료해도 rewardPoint를 1회 적립한다")
    void 자동_완료로_방문_인증하면_포인트를_1회_적립한다() {
        // given
        Long scheduleId = 7L;
        Member senior = seniorMember();
        HealthSchedule schedule = scheduleWithId(senior, LocalDateTime.now().minusMinutes(5), scheduleId);
        given(healthScheduleRepository.findByMemberIdAndStatusAndDate(
                eq(senior.getId()), eq(ProgressStatus.UPCOMING), beforeDateCaptor.capture(), beforeDateCaptor.capture()))
                .willReturn(List.of(schedule));

        // when
        healthScheduleProgressService.completeArrivedSchedules(senior.getId(), 37.5, 127.0);

        // then
        assertThat(schedule.getProgressStatus()).isEqualTo(ProgressStatus.COMPLETED);
        assertThat(schedule.getIsReward()).isTrue();
        then(seniorProfileService).should()
                .addPointsToMember(senior.getId(), 100L, "건강 일정 방문", "HEALTH_SCHEDULE_REWARD:" + scheduleId);
    }

    @Test
    @DisplayName("이미 적립된 일정을 다시 완료 처리하면 포인트를 적립하지 않는다")
    void 이미_적립된_일정을_다시_완료하면_포인트를_적립하지_않는다() {
        // given
        Long scheduleId = 1L;
        Member senior = seniorMember();
        HealthSchedule schedule = scheduleWithId(senior, LocalDateTime.now().minusMinutes(5), scheduleId);
        schedule.claimReward();
        given(memberUtil.getCurrentMember()).willReturn(senior);
        given(healthScheduleRepository.findById(scheduleId)).willReturn(Optional.of(schedule));
        given(seniorLocationRepository.findBySeniorId(senior.getId()))
                .willReturn(Optional.of(SeniorLocation.of(senior.getId(), 37.5, 127.0)));

        // when
        healthScheduleProgressService.completeSchedule(scheduleId);

        // then
        assertThat(schedule.getProgressStatus()).isEqualTo(ProgressStatus.COMPLETED);
        then(seniorProfileService).should(never())
                .addPointsToMember(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("소유자가 보호자인 일정을 완료 처리하면 상태만 바뀌고 포인트를 적립하지 않는다")
    void 보호자_소유_일정을_완료하면_포인트를_적립하지_않는다() {
        // given
        Member guardian = guardianMember();
        HealthSchedule schedule = scheduleWithId(guardian, LocalDateTime.now().minusMinutes(5), 9L);
        given(healthScheduleRepository.findByMemberIdAndStatusAndDate(
                eq(guardian.getId()), eq(ProgressStatus.UPCOMING), beforeDateCaptor.capture(), beforeDateCaptor.capture()))
                .willReturn(List.of(schedule));

        // when
        healthScheduleProgressService.completeArrivedSchedules(guardian.getId(), 37.5, 127.0);

        // then
        assertThat(schedule.getProgressStatus()).isEqualTo(ProgressStatus.COMPLETED);
        assertThat(schedule.getIsReward()).isFalse();
        then(seniorProfileService).should(never())
                .addPointsToMember(anyLong(), anyLong(), anyString(), any());
    }
}
