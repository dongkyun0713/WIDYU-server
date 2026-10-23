package com.widyu.home.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.widyu.global.util.MemberUtil;
import com.widyu.goal.healthschedule.HealthScheduleFixture;
import com.widyu.goal.healthschedule.dto.response.TodayHealthScheduleItem;
import com.widyu.goal.healthschedule.repository.HealthScheduleRepository;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.goal.medicineschedule.repository.MedicineScheduleRepository;
import com.widyu.goal.walk.repository.WalkRepository;
import com.widyu.healthschedule.ProgressStatus;
import com.widyu.heart.repository.HeartRateResultRepository;
import com.widyu.home.dto.response.SeniorHomeCardsResponse;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("SeniorHomeService 건강달력 카드 오늘 일정 단위 테스트")
class SeniorHomeServiceTodayScheduleTest {

    private static final Long SENIOR_ID = 11L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 10, 0);

    @Mock private MemberUtil memberUtil;
    @Mock private MedicineScheduleRepository medicineScheduleRepository;
    @Mock private MedicationProofRepository medicationProofRepository;
    @Mock private WalkRepository walkRepository;
    @Mock private HealthScheduleRepository healthScheduleRepository;
    @Mock private HeartRateResultRepository heartRateResultRepository;
    @Mock private HomeAlbumRecommendationService albumRecommendationService;

    @InjectMocks private SeniorHomeService seniorHomeService;

    @Test
    @DisplayName("오늘 지난 일정·완료 일정·남은 일정과 내일 일정이 있으면 오늘 일정만 오름차순으로 담고 다음 일정은 오늘 남은 일정을 반환한다")
    void 오늘_일정만_담고_다음_일정은_오늘_남은_일정이다() {
        // given
        Member senior = senior();
        LocalDateTime todayStart = NOW.toLocalDate().atStartOfDay();
        given(memberUtil.getCurrentMember()).willReturn(senior);
        given(healthScheduleRepository.findByMemberIdAndWeek(SENIOR_ID, todayStart, NOW.plusMonths(1)))
                .willReturn(List.of(
                        HealthScheduleFixture.schedule(senior, 4L, NOW.plusDays(1).withHour(9), ProgressStatus.UPCOMING),
                        HealthScheduleFixture.schedule(senior, 3L, NOW.withHour(11), ProgressStatus.UPCOMING),
                        HealthScheduleFixture.schedule(senior, 2L, NOW.withHour(9).withMinute(30), ProgressStatus.UPCOMING),
                        HealthScheduleFixture.schedule(senior, 1L, NOW.withHour(8), ProgressStatus.COMPLETED)));

        // when
        SeniorHomeCardsResponse response = seniorHomeService.getHomeCards(NOW);

        // then
        assertThat(response.todaySchedules())
                .extracting(TodayHealthScheduleItem::healthScheduleId, TodayHealthScheduleItem::progressStatus,
                        TodayHealthScheduleItem::elapsed)
                .containsExactly(
                        tuple(1L, ProgressStatus.COMPLETED, true),
                        tuple(2L, ProgressStatus.UPCOMING, true),
                        tuple(3L, ProgressStatus.UPCOMING, false));
        assertThat(response.healthSchedule().scheduledAt()).isEqualTo(NOW.withHour(11));
        assertThat(response.healthSchedule().dday()).isZero();
        then(healthScheduleRepository).should().findByMemberIdAndWeek(SENIOR_ID, todayStart, NOW.plusMonths(1));
        then(healthScheduleRepository).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("현재 시각 이후 일정이 없으면 다음 일정은 null이고 오늘 일정 목록은 유지한다")
    void 남은_일정이_없으면_다음_일정이_null이고_오늘_목록을_유지한다() {
        // given
        Member senior = senior();
        given(memberUtil.getCurrentMember()).willReturn(senior);
        given(healthScheduleRepository.findByMemberIdAndWeek(SENIOR_ID, NOW.toLocalDate().atStartOfDay(), NOW.plusMonths(1)))
                .willReturn(List.of(
                        HealthScheduleFixture.schedule(senior, 1L, NOW.withHour(7), ProgressStatus.UPCOMING),
                        HealthScheduleFixture.schedule(senior, 2L, NOW.withHour(8), ProgressStatus.COMPLETED)));

        // when
        SeniorHomeCardsResponse response = seniorHomeService.getHomeCards(NOW);

        // then
        assertThat(response.healthSchedule()).isNull();
        assertThat(response.todaySchedules())
                .extracting(TodayHealthScheduleItem::healthScheduleId, TodayHealthScheduleItem::progressStatus,
                        TodayHealthScheduleItem::elapsed)
                .containsExactly(
                        tuple(1L, ProgressStatus.INCOMPLETE, true),
                        tuple(2L, ProgressStatus.COMPLETED, true));
    }

    @Test
    @DisplayName("오늘 일정이 없으면 오늘 일정 목록은 빈 배열을 반환한다")
    void 오늘_일정이_없으면_빈_배열을_반환한다() {
        // given
        given(memberUtil.getCurrentMember()).willReturn(senior());

        // when
        SeniorHomeCardsResponse response = seniorHomeService.getHomeCards(NOW);

        // then
        assertThat(response.todaySchedules()).isNotNull().isEmpty();
        assertThat(response.healthSchedule()).isNull();
    }

    private Member senior() {
        Member member = Member.createMember(MemberType.SENIOR, "부모님", "01011112222");
        ReflectionTestUtils.setField(member, "id", SENIOR_ID);
        return member;
    }
}
