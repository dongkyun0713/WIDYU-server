package com.widyu.goal.walk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.goal.walk.dto.request.UpdateStepsRequest;
import com.widyu.goal.walk.dto.response.UpdateStepsResponse;
import com.widyu.goal.walk.dto.response.WalkMonthlyResponse;
import com.widyu.goal.walk.repository.WalkRepository;
import com.widyu.member.Family;
import com.widyu.member.Member;
import com.widyu.member.SeniorProfile;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("WalkService 월별 조회 단위 테스트")
class WalkServiceTest {

    @Mock private WalkRepository walkRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private MemberUtil memberUtil;

    @InjectMocks private WalkService walkService;

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
        Member member = org.mockito.Mockito.mock(Member.class);
        Family family = org.mockito.Mockito.mock(Family.class);
        SeniorProfile seniorProfile = SeniorProfile.createSeniorProfile(
                member, family, "주소", "INV1234", LocalDate.of(1950, 1, 1));
        given(member.getSeniorProfile()).willReturn(seniorProfile);
        given(memberUtil.getCurrentMember()).willReturn(member);

        Walk walk = Walk.createWithGoal(member, LocalDate.now(), 5000);
        given(walkRepository.findByMemberAndWalkDate(any(), any())).willReturn(Optional.of(walk));

        long pointsBefore = seniorProfile.getPoints();

        // when
        UpdateStepsResponse first = walkService.updateSteps(new UpdateStepsRequest(6000, LocalDate.now()));
        UpdateStepsResponse second = walkService.updateSteps(new UpdateStepsRequest(8000, LocalDate.now()));

        // then
        assertThat(first.achieved()).isTrue();
        assertThat(second.achieved()).isTrue();
        assertThat(walk.getActualSteps()).isEqualTo(8000);
        assertThat(seniorProfile.getPoints()).isEqualTo(pointsBefore + 25L);
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
