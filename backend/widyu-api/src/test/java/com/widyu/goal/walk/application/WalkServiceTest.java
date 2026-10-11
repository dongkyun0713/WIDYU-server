package com.widyu.goal.walk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.goal.event.GuardianGoalChangedEvent;
import com.widyu.goal.walk.dto.request.SetGoalRequest;
import com.widyu.goal.walk.dto.request.UpdateStepsRequest;
import com.widyu.goal.walk.dto.response.UpdateStepsResponse;
import com.widyu.goal.walk.dto.response.WalkMonthlyResponse;
import com.widyu.goal.walk.repository.WalkRepository;
import com.widyu.member.Family;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.application.SeniorProfileService;
import com.widyu.member.repository.MemberRepository;
import com.widyu.walk.Walk;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import com.widyu.fcm.NotificationType;

@ExtendWith(MockitoExtension.class)
@DisplayName("WalkService 월별 조회 단위 테스트")
class WalkServiceTest {

    @Mock private WalkRepository walkRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private MemberUtil memberUtil;
    @Mock private SeniorProfileService seniorProfileService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private WalkService walkService;

    @Test
    @DisplayName("보호자가 시니어 걷기 목표를 처음 설정하면 W02 이벤트를 한 번 발행한다")
    void 보호자가_걷기_목표를_처음_설정하면_W02_이벤트를_발행한다() {
        // given
        Member guardian = member(1L, MemberType.GUARDIAN);
        Member senior = member(2L, MemberType.SENIOR);
        SeniorProfile profile = seniorProfile(senior);
        given(memberRepository.findById(2L)).willReturn(Optional.of(senior));
        given(memberUtil.getCurrentMember()).willReturn(guardian);

        // when
        walkService.setOrUpdateGoal(2L, new SetGoalRequest(7000));

        // then
        assertThat(profile.getDefaultWalkGoal()).isEqualTo(7000);
        ArgumentCaptor<GuardianGoalChangedEvent> events = ArgumentCaptor.forClass(GuardianGoalChangedEvent.class);
        then(eventPublisher).should(times(1)).publishEvent(events.capture());
        assertThat(events.getValue().seniorId()).isEqualTo(2L);
        assertThat(events.getValue().type()).isEqualTo(NotificationType.WALK_GOAL_CHANGED);
        assertThat(events.getValue().goalSteps()).isEqualTo(7000);
    }

    @Test
    @DisplayName("보호자가 시니어 걷기 목표를 수정하면 기존 오늘 목표를 유지하고 새 목표를 알린다")
    void 보호자가_걷기_목표를_수정하면_오늘_목표를_유지하고_새_목표를_알린다() {
        // given
        Member guardian = member(1L, MemberType.GUARDIAN);
        Member senior = member(2L, MemberType.SENIOR);
        SeniorProfile profile = seniorProfile(senior);
        profile.updateDefaultWalkGoal(5000);
        given(memberRepository.findById(2L)).willReturn(Optional.of(senior));
        given(memberUtil.getCurrentMember()).willReturn(guardian);

        // when
        walkService.setOrUpdateGoal(2L, new SetGoalRequest(8000));

        // then
        assertThat(profile.getDefaultWalkGoal()).isEqualTo(8000);
        ArgumentCaptor<Walk> todayWalk = ArgumentCaptor.forClass(Walk.class);
        then(walkRepository).should(times(1)).save(todayWalk.capture());
        assertThat(todayWalk.getValue().getGoalSteps()).isEqualTo(5000);
        ArgumentCaptor<GuardianGoalChangedEvent> events = ArgumentCaptor.forClass(GuardianGoalChangedEvent.class);
        then(eventPublisher).should(times(1)).publishEvent(events.capture());
        assertThat(events.getValue().goalSteps()).isEqualTo(8000);
    }

    @Test
    @DisplayName("시니어가 자기 걷기 목표를 설정하면 보호자 변경 이벤트를 발행하지 않는다")
    void 시니어가_자기_걷기_목표를_설정하면_이벤트를_발행하지_않는다() {
        // given
        Member senior = member(2L, MemberType.SENIOR);
        SeniorProfile profile = seniorProfile(senior);
        given(memberUtil.getCurrentMember()).willReturn(senior);

        // when
        walkService.setOrUpdateGoal(null, new SetGoalRequest(7000));

        // then
        assertThat(profile.getDefaultWalkGoal()).isEqualTo(7000);
        then(eventPublisher).should(never()).publishEvent(any());
    }

    private Member member(Long id, MemberType type) {
        Member member = Member.createMember(type, type.name(), "01011112222");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    private SeniorProfile seniorProfile(Member senior) {
        SeniorProfile profile = SeniorProfile.createSeniorProfile(
                senior, Family.createFamily("ABC123"), "서울시", "INV1234", LocalDate.of(1950, 1, 1));
        ReflectionTestUtils.setField(senior, "seniorProfile", profile);
        return profile;
    }

    @Test
    @DisplayName("기록이 없는 과거 날짜는 기본 목표를 소급 적용하지 않고 오늘·미래만 기본 목표로 채운다")
    void 기록이_없는_과거_날짜는_기본_목표를_소급하지_않는다() {
        // given
        Long memberId = 1L;
        Member member = org.mockito.Mockito.mock(Member.class);
        SeniorProfile seniorProfile = org.mockito.Mockito.mock(SeniorProfile.class);
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
        given(member.getId()).willReturn(memberId);
        given(member.getSeniorProfile()).willReturn(seniorProfile);
        given(seniorProfile.hasDefaultWalkGoal()).willReturn(true);
        given(seniorProfile.getDefaultWalkGoal()).willReturn(5000);
        given(walkRepository.countAchievedGoals(anyLong(), any(), any())).willReturn(0L);
        given(walkRepository.countTotalRecords(anyLong(), any(), any())).willReturn(0L);
        given(walkRepository.findByMemberAndWalkDateBetweenOrderByWalkDateAsc(any(), any(), any()))
                .willReturn(List.of());

        YearMonth currentMonth = YearMonth.now();
        LocalDate today = LocalDate.now();

        // when
        WalkMonthlyResponse response = walkService.getMonthlyStats(
                currentMonth.getYear(), currentMonth.getMonthValue(), memberId);

        // then
        List<LocalDate> dates = response.dailyData().stream()
                .map(daily -> LocalDate.parse(daily.date()))
                .toList();
        assertThat(dates).isNotEmpty();
        assertThat(dates).allSatisfy(date -> assertThat(date).isAfterOrEqualTo(today));

        long expectedDays = currentMonth.atEndOfMonth().getDayOfMonth() - today.getDayOfMonth() + 1;
        assertThat(dates).hasSize((int) expectedDays);
    }

    @Test
    @DisplayName("목표를 초과한 뒤 다시 연동하면 걸음 수가 갱신되고 포인트는 중복 지급되지 않는다")
    void 목표_초과_후_재연동하면_걸음수가_갱신되고_포인트는_1회만_지급한다() {
        // given
        Long memberId = 1L;
        Member member = org.mockito.Mockito.mock(Member.class);
        Family family = org.mockito.Mockito.mock(Family.class);
        SeniorProfile seniorProfile = SeniorProfile.createSeniorProfile(
                member, family, "주소", "INV1234", LocalDate.of(1950, 1, 1));
        given(member.getId()).willReturn(memberId);
        given(member.getSeniorProfile()).willReturn(seniorProfile);
        given(memberUtil.getCurrentMember()).willReturn(member);

        Walk walk = Walk.createWithGoal(member, LocalDate.now(), 5000);
        given(walkRepository.findByMemberAndWalkDate(any(), any())).willReturn(Optional.of(walk));

        // when
        UpdateStepsResponse first = walkService.updateSteps(new UpdateStepsRequest(6000, LocalDate.now()));
        UpdateStepsResponse second = walkService.updateSteps(new UpdateStepsRequest(8000, LocalDate.now()));

        // then
        assertThat(first.achieved()).isTrue();
        assertThat(second.achieved()).isTrue();
        assertThat(walk.getActualSteps()).isEqualTo(8000);
        assertThat(walk.isRewarded()).isTrue();
        then(seniorProfileService).should(times(1))
                .addPointsToMember(eq(memberId), eq(25L), eq("걷기 목표 달성"), startsWith("WALK_REWARD:"));
    }

    @Test
    @DisplayName("시니어 프로필이 없어도 걸음 수는 갱신되고 포인트는 지급되지 않는다")
    void 프로필이_없으면_걸음수만_갱신하고_포인트는_지급하지_않는다() {
        // given
        Member member = org.mockito.Mockito.mock(Member.class);
        given(member.getSeniorProfile()).willReturn(null);
        given(memberUtil.getCurrentMember()).willReturn(member);

        Walk walk = Walk.createWithGoal(member, LocalDate.now(), 5000);
        given(walkRepository.findByMemberAndWalkDate(any(), any())).willReturn(Optional.of(walk));

        // when
        UpdateStepsResponse response = walkService.updateSteps(new UpdateStepsRequest(6000, LocalDate.now()));

        // then
        assertThat(response.achieved()).isTrue();
        assertThat(walk.getActualSteps()).isEqualTo(6000);
        assertThat(walk.isRewarded()).isFalse();
        then(seniorProfileService).should(never())
                .addPointsToMember(any(), any(), any(), any());
    }

    @Test
    @DisplayName("최근 7일 중 가장 이른 날짜를 연동하면 해당 날짜의 걸음 수를 갱신한다")
    void 최근_7일_중_가장_이른_날짜를_연동하면_해당_날짜의_걸음수를_갱신한다() {
        // given
        Member member = org.mockito.Mockito.mock(Member.class);
        LocalDate syncDate = LocalDate.now().minusDays(6);
        Walk walk = Walk.createWithGoal(member, syncDate, 5000);
        given(memberUtil.getCurrentMember()).willReturn(member);
        given(walkRepository.findByMemberAndWalkDate(member, syncDate)).willReturn(Optional.of(walk));

        // when
        UpdateStepsResponse response = walkService.updateSteps(new UpdateStepsRequest(6000, syncDate));

        // then
        assertThat(response.achieved()).isTrue();
        assertThat(walk.getActualSteps()).isEqualTo(6000);
        assertThat(walk.getWalkDate()).isEqualTo(syncDate);
    }

    @Test
    @DisplayName("7일 전 날짜를 연동하면 BAD_REQUEST 예외가 발생한다")
    void 일주일_전_날짜를_연동하면_BAD_REQUEST_예외가_발생한다() {
        // given
        LocalDate expiredDate = LocalDate.now().minusDays(7);

        // when & then
        assertThatThrownBy(() -> walkService.updateSteps(new UpdateStepsRequest(6000, expiredDate)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("미래 날짜를 연동하면 BAD_REQUEST 예외가 발생한다")
    void 미래_날짜를_연동하면_BAD_REQUEST_예외가_발생한다() {
        // given
        LocalDate futureDate = LocalDate.now().plusDays(1);

        // when & then
        assertThatThrownBy(() -> walkService.updateSteps(new UpdateStepsRequest(6000, futureDate)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BAD_REQUEST);
    }
}
