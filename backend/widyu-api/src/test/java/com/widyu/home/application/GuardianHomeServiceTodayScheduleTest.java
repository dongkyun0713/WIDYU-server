package com.widyu.home.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.widyu.global.util.MemberUtil;
import com.widyu.goal.healthschedule.HealthScheduleFixture;
import com.widyu.goal.healthschedule.dto.response.TodayHealthScheduleItem;
import com.widyu.goal.healthschedule.repository.HealthScheduleRepository;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.goal.medicineschedule.repository.MedicineScheduleRepository;
import com.widyu.goal.walk.repository.WalkRepository;
import com.widyu.healthschedule.HealthSchedule;
import com.widyu.healthschedule.ProgressStatus;
import com.widyu.heart.repository.HeartRateResultRepository;
import com.widyu.home.dto.response.GuardianHomeCardsResponse;
import com.widyu.location.access.application.LocationAccessLogService;
import com.widyu.location.realtime.application.RealtimeLocationService;
import com.widyu.member.Family;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
@DisplayName("GuardianHomeService 건강달력 카드 오늘 일정 단위 테스트")
class GuardianHomeServiceTodayScheduleTest {

    private static final Long GUARDIAN_ID = 1L;
    private static final Long SENIOR_ID = 2L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 10, 0);
    private static final LocalDateTime TODAY_START = NOW.toLocalDate().atStartOfDay();

    @Mock private MemberUtil memberUtil;
    @Mock private MemberRepository memberRepository;
    @Mock private FamilyMembershipRepository familyMembershipRepository;
    @Mock private SeniorProfileRepository seniorProfileRepository;
    @Mock private HeartRateResultRepository heartRateResultRepository;
    @Mock private MedicineScheduleRepository medicineScheduleRepository;
    @Mock private MedicationProofRepository medicationProofRepository;
    @Mock private HealthScheduleRepository healthScheduleRepository;
    @Mock private WalkRepository walkRepository;
    @Mock private HomeAlbumRecommendationService albumRecommendationService;
    @Mock private RealtimeLocationService realtimeLocationService;
    @Mock private LocationAccessLogService locationAccessLogService;

    @InjectMocks private GuardianHomeService guardianHomeService;

    @Test
    @DisplayName("오늘 지난 일정·완료 일정·남은 일정이 있으면 오늘 일정을 오름차순으로 담고 다음 일정은 오늘 남은 일정을 반환한다")
    void 오늘_일정을_담고_다음_일정은_오늘_남은_일정이다() {
        // given
        Member senior = givenConnectedSenior();
        HealthSchedule next = HealthScheduleFixture.schedule(senior, 3L, NOW.withHour(11), ProgressStatus.UPCOMING);
        given(healthScheduleRepository.findFirstByMemberIdAndScheduledAtAfterOrderByScheduledAtAsc(SENIOR_ID, NOW))
                .willReturn(Optional.of(next));
        given(healthScheduleRepository.findByMemberIdAndDate(SENIOR_ID, TODAY_START, TODAY_START.plusDays(1)))
                .willReturn(List.of(
                        next,
                        HealthScheduleFixture.schedule(senior, 2L, NOW.withHour(9).withMinute(30), ProgressStatus.UPCOMING),
                        HealthScheduleFixture.schedule(senior, 1L, NOW.withHour(8), ProgressStatus.COMPLETED)));

        // when
        GuardianHomeCardsResponse response = guardianHomeService.getHomeCards(SENIOR_ID, NOW);

        // then
        assertThat(response.todaySchedules())
                .extracting(TodayHealthScheduleItem::healthScheduleId, TodayHealthScheduleItem::progressStatus,
                        TodayHealthScheduleItem::elapsed)
                .containsExactly(
                        tuple(1L, ProgressStatus.COMPLETED, true),
                        tuple(2L, ProgressStatus.UPCOMING, true),
                        tuple(3L, ProgressStatus.UPCOMING, false));
        assertThat(response.healthSchedule().scheduleId()).isEqualTo(3L);
        then(healthScheduleRepository).should()
                .findFirstByMemberIdAndScheduledAtAfterOrderByScheduledAtAsc(SENIOR_ID, NOW);
        then(healthScheduleRepository).should()
                .findByMemberIdAndDate(SENIOR_ID, TODAY_START, TODAY_START.plusDays(1));
        then(healthScheduleRepository).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("현재 시각 이후 일정이 없으면 다음 일정은 null이고 오늘 일정 목록은 유지한다")
    void 남은_일정이_없으면_다음_일정이_null이고_오늘_목록을_유지한다() {
        // given
        Member senior = givenConnectedSenior();
        given(healthScheduleRepository.findByMemberIdAndDate(SENIOR_ID, TODAY_START, TODAY_START.plusDays(1)))
                .willReturn(List.of(
                        HealthScheduleFixture.schedule(senior, 2L, NOW.withHour(8), ProgressStatus.COMPLETED),
                        HealthScheduleFixture.schedule(senior, 1L, NOW.withHour(7), ProgressStatus.UPCOMING)));

        // when
        GuardianHomeCardsResponse response = guardianHomeService.getHomeCards(SENIOR_ID, NOW);

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
        givenConnectedSenior();

        // when
        GuardianHomeCardsResponse response = guardianHomeService.getHomeCards(SENIOR_ID, NOW);

        // then
        assertThat(response.todaySchedules()).isNotNull().isEmpty();
        assertThat(response.healthSchedule()).isNull();
    }

    private Member givenConnectedSenior() {
        Member guardian = member(GUARDIAN_ID, MemberType.GUARDIAN);
        Member senior = member(SENIOR_ID, MemberType.SENIOR);
        SeniorProfile seniorProfile = SeniorProfile.createSeniorProfile(
                senior, Family.createFamily("ABC123"), "서울시", "INV1234", LocalDate.of(1950, 1, 1));

        given(memberUtil.getCurrentMember()).willReturn(guardian);
        given(memberRepository.findById(SENIOR_ID)).willReturn(Optional.of(senior));
        given(seniorProfileRepository.findByMemberId(SENIOR_ID)).willReturn(Optional.of(seniorProfile));
        given(familyMembershipRepository.existsByFamilyIdAndGuardianId(any(), eq(GUARDIAN_ID))).willReturn(true);
        return senior;
    }

    private Member member(Long id, MemberType type) {
        Member member = Member.createMember(type, "회원", "01011112222");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }
}
