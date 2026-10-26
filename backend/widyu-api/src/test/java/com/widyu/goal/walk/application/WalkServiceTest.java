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
import com.widyu.fcm.event.goal.dto.GoalAchievedEvent;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import com.widyu.fcm.NotificationPolicy;
import org.mockito.ArgumentCaptor;

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
        assertThat(events.getValue().type()).isEqualTo(NotificationPolicy.walk("W02"));
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
    @DisplayName("현재 월을 조회하면 모든 날짜를 0걸음으로 채우고 기본 목표는 오늘부터 적용한다")
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
        assertThat(dates).containsExactlyElementsOf(
                currentMonth.atDay(1).datesUntil(currentMonth.plusMonths(1).atDay(1)).toList());
        assertThat(response.dailyData()).allSatisfy(daily -> {
            assertThat(daily.actual()).isZero();
            if (LocalDate.parse(daily.date()).isBefore(today)) {
                assertThat(daily.goal()).isNull();
            } else {
                assertThat(daily.goal()).isEqualTo(5000);
            }
        });
        assertThat(response.summary().current().total()).isZero();
    }

    @Test
    @DisplayName("10월에 일부 기록만 있으면 저장된 값을 유지하고 미수신 날짜는 다음 기록의 목표와 0걸음으로 반환한다")
    void 일부_날짜만_기록된_10월도_31일을_반환한다() {
        // given
        Member senior = member(2L, MemberType.SENIOR);
        seniorProfile(senior).updateDefaultWalkGoal(8000);
        YearMonth month = YearMonth.of(2025, 10);
        Walk first = Walk.createWithGoal(senior, month.atDay(1), 5000);
        first.updateActualSteps(6000);
        Walk middle = Walk.createWithGoal(senior, month.atDay(15), 7000);
        middle.updateActualSteps(3200);
        Walk last = Walk.createWithGoal(senior, month.atEndOfMonth(), 9000);
        given(memberRepository.findById(2L)).willReturn(Optional.of(senior));
        given(walkRepository.findByMemberAndWalkDateBetweenOrderByWalkDateAsc(
                senior, month.atDay(1), month.atEndOfMonth())).willReturn(List.of(first, middle, last));
        given(walkRepository.findFirstByMemberOrderByWalkDateAsc(senior)).willReturn(Optional.of(first));
        YearMonth previousMonth = month.minusMonths(1);
        given(walkRepository.countAchievedGoals(2L, previousMonth.atDay(1), previousMonth.atEndOfMonth()))
                .willReturn(2L);
        given(walkRepository.countTotalRecords(2L, previousMonth.atDay(1), previousMonth.atEndOfMonth()))
                .willReturn(4L);
        given(walkRepository.countAchievedGoals(2L, month.atDay(1), month.atEndOfMonth())).willReturn(1L);
        given(walkRepository.countTotalRecords(2L, month.atDay(1), month.atEndOfMonth())).willReturn(3L);

        // when
        WalkMonthlyResponse response = walkService.getMonthlyStats(2025, 10, 2L);

        // then
        assertThat(response.dailyData()).extracting(WalkMonthlyResponse.WalkDaily::date)
                .containsExactlyElementsOf(month.atDay(1).datesUntil(month.plusMonths(1).atDay(1))
                        .map(LocalDate::toString).toList());
        assertThat(response.dailyData().get(0)).isEqualTo(
                WalkMonthlyResponse.WalkDaily.of("2025-10-01", 5000, 6000));
        assertThat(response.dailyData().get(14)).isEqualTo(
                WalkMonthlyResponse.WalkDaily.of("2025-10-15", 7000, 3200));
        assertThat(response.dailyData().get(30)).isEqualTo(
                WalkMonthlyResponse.WalkDaily.of("2025-10-31", 9000, 0));
        assertThat(response.dailyData().subList(1, 14)).allSatisfy(daily -> {
            assertThat(daily.actual()).isZero();
            assertThat(daily.goal()).isEqualTo(7000);
        });
        assertThat(response.dailyData().subList(15, 30)).allSatisfy(daily -> {
            assertThat(daily.actual()).isZero();
            assertThat(daily.goal()).isEqualTo(9000);
        });
        // 첫 기록(10/1) 이후 미수신 28일도 목표가 있던 날이라 분모에 들어간다
        assertThat(response.summary().current()).isEqualTo(
                WalkMonthlyResponse.WalkSummary.MonthStats.of(1, 31));
        assertThat(response.summary().previous()).isEqualTo(
                WalkMonthlyResponse.WalkSummary.MonthStats.of(2, 4));
    }

    @Test
    @DisplayName("첫 기록 이전의 지난 날짜는 목표 없음, 이후 미수신 날짜는 다음 달 기록의 목표로 반환한다")
    void 첫_기록_이전은_목표_없음이고_이후_미수신은_다음_기록_목표를_쓴다() {
        // given
        Member senior = member(2L, MemberType.SENIOR);
        seniorProfile(senior).updateDefaultWalkGoal(8000);
        YearMonth month = YearMonth.of(2025, 10);
        Walk firstEver = Walk.createWithGoal(senior, month.atDay(10), 5000);
        Walk nextMonth = Walk.createWithGoal(senior, LocalDate.of(2025, 11, 5), 6000);
        given(memberRepository.findById(2L)).willReturn(Optional.of(senior));
        given(walkRepository.findByMemberAndWalkDateBetweenOrderByWalkDateAsc(
                senior, month.atDay(1), month.atEndOfMonth())).willReturn(List.of(firstEver));
        given(walkRepository.findFirstByMemberOrderByWalkDateAsc(senior)).willReturn(Optional.of(firstEver));
        given(walkRepository.findFirstByMemberAndWalkDateAfterOrderByWalkDateAsc(senior, month.atEndOfMonth()))
                .willReturn(Optional.of(nextMonth));

        // when
        WalkMonthlyResponse response = walkService.getMonthlyStats(2025, 10, 2L);

        // then
        assertThat(response.dailyData().subList(0, 9)).allSatisfy(daily -> {
            assertThat(daily.actual()).isZero();
            assertThat(daily.goal()).isNull();
        });
        assertThat(response.dailyData().get(9)).isEqualTo(
                WalkMonthlyResponse.WalkDaily.of("2025-10-10", 5000, 0));
        assertThat(response.dailyData().subList(10, 31)).allSatisfy(daily -> {
            assertThat(daily.actual()).isZero();
            assertThat(daily.goal()).isEqualTo(6000);
        });
    }

    @Test
    @DisplayName("마지막 기록 이후의 지난 미수신 날짜는 현재 기본 목표로 반환한다")
    void 마지막_기록_이후_미수신은_현재_기본_목표를_쓴다() {
        // given
        Member senior = member(2L, MemberType.SENIOR);
        seniorProfile(senior).updateDefaultWalkGoal(8000);
        YearMonth month = YearMonth.of(2025, 10);
        Walk lastWalk = Walk.createWithGoal(senior, month.atDay(5), 5000);
        given(memberRepository.findById(2L)).willReturn(Optional.of(senior));
        given(walkRepository.findByMemberAndWalkDateBetweenOrderByWalkDateAsc(
                senior, month.atDay(1), month.atEndOfMonth())).willReturn(List.of(lastWalk));
        given(walkRepository.findFirstByMemberOrderByWalkDateAsc(senior))
                .willReturn(Optional.of(Walk.createWithGoal(senior, LocalDate.of(2025, 9, 1), 4000)));

        // when
        WalkMonthlyResponse response = walkService.getMonthlyStats(2025, 10, 2L);

        // then
        assertThat(response.dailyData().subList(0, 4)).allSatisfy(daily ->
                assertThat(daily.goal()).isEqualTo(5000));
        assertThat(response.dailyData().subList(5, 31)).allSatisfy(daily -> {
            assertThat(daily.actual()).isZero();
            assertThat(daily.goal()).isEqualTo(8000);
        });
    }

    @Test
    @DisplayName("이번 달 summary의 전체 일수는 걸음 기록이 없어도 오늘까지 목표가 있던 날을 센다")
    void 이번_달_summary는_오늘까지_목표가_있던_날을_센다() {
        // given: 첫 기록은 지난달 이전, 이번 달 기록은 없음
        Member senior = member(2L, MemberType.SENIOR);
        seniorProfile(senior).updateDefaultWalkGoal(8000);
        YearMonth month = YearMonth.now();
        LocalDate today = LocalDate.now();
        given(memberRepository.findById(2L)).willReturn(Optional.of(senior));
        given(walkRepository.findByMemberAndWalkDateBetweenOrderByWalkDateAsc(
                senior, month.atDay(1), month.atEndOfMonth())).willReturn(List.of());
        given(walkRepository.findFirstByMemberOrderByWalkDateAsc(senior))
                .willReturn(Optional.of(Walk.createWithGoal(senior, month.minusMonths(2).atDay(1), 8000)));

        // when
        WalkMonthlyResponse response = walkService.getMonthlyStats(
                month.getYear(), month.getMonthValue(), 2L);

        // then: 미래 날짜는 빼고 1일~오늘만 센다
        assertThat(response.summary().current()).isEqualTo(
                WalkMonthlyResponse.WalkSummary.MonthStats.of(0, today.getDayOfMonth()));
        assertThat(response.summary().previous()).isEqualTo(
                WalkMonthlyResponse.WalkSummary.MonthStats.of(0, month.minusMonths(1).lengthOfMonth()));
    }

    @ParameterizedTest
    @CsvSource({"2024, 2, 29", "2025, 2, 28", "2025, 4, 30", "2025, 10, 31"})
    @DisplayName("기록과 기본 목표가 없는 월을 조회하면 월 길이만큼 0걸음과 미설정 목표를 반환한다")
    void 기록과_기본_목표가_없어도_월_전체_날짜를_반환한다(int year, int month, int days) {
        // given
        Member senior = member(2L, MemberType.SENIOR);
        seniorProfile(senior);
        given(memberUtil.getCurrentMember()).willReturn(senior);
        YearMonth requestedMonth = YearMonth.of(year, month);

        // when
        WalkMonthlyResponse response = walkService.getMonthlyStats(year, month, null);

        // then
        assertThat(response.dailyData()).hasSize(days)
                .extracting(WalkMonthlyResponse.WalkDaily::date)
                .containsExactlyElementsOf(requestedMonth.atDay(1)
                        .datesUntil(requestedMonth.plusMonths(1).atDay(1)).map(LocalDate::toString).toList());
        assertThat(response.dailyData()).allSatisfy(daily -> {
            assertThat(daily.actual()).isZero();
            assertThat(daily.goal()).isNull();
        });
    }

    @Test
    @DisplayName("시니어 프로필 없이 현재 월을 조회하면 모든 날짜를 0걸음과 미설정 목표로 반환한다")
    void 프로필이_없어도_현재_월_전체_날짜를_반환한다() {
        // given
        Member senior = member(2L, MemberType.SENIOR);
        given(memberUtil.getCurrentMember()).willReturn(senior);
        YearMonth month = YearMonth.now();

        // when
        WalkMonthlyResponse response = walkService.getMonthlyStats(month.getYear(), month.getMonthValue(), null);

        // then
        assertThat(response.dailyData()).hasSize(month.lengthOfMonth()).allSatisfy(daily -> {
            assertThat(daily.actual()).isZero();
            assertThat(daily.goal()).isNull();
        });
    }

    @Test
    @DisplayName("미래 월을 조회하면 모든 날짜를 기본 목표와 0걸음으로 반환한다")
    void 미래_월은_기본_목표와_0걸음으로_채운다() {
        // given
        Member senior = member(2L, MemberType.SENIOR);
        seniorProfile(senior).updateDefaultWalkGoal(5000);
        given(memberUtil.getCurrentMember()).willReturn(senior);
        YearMonth month = YearMonth.now().plusMonths(1);

        // when
        WalkMonthlyResponse response = walkService.getMonthlyStats(month.getYear(), month.getMonthValue(), null);

        // then
        assertThat(response.dailyData()).hasSize(month.lengthOfMonth()).allSatisfy(daily -> {
            assertThat(daily.actual()).isZero();
            assertThat(daily.goal()).isEqualTo(5000);
        });
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
        ReflectionTestUtils.setField(walk, "id", 7L);
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
                .addGoalRewardPoints(eq(memberId), eq(25L), eq("걷기 목표 달성"), startsWith("WALK_REWARD:"));
        ArgumentCaptor<GoalAchievedEvent> event = ArgumentCaptor.forClass(GoalAchievedEvent.class);
        then(eventPublisher).should(times(1)).publishEvent(event.capture());
        assertThat(event.getValue().eventId()).isEqualTo("G01:W:7");
        assertThat(event.getValue().points()).isEqualTo(25L);
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
                .addGoalRewardPoints(any(), any(), any(), any());
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
