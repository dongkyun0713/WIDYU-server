package com.widyu.home.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.util.MemberUtil;
import com.widyu.goal.healthschedule.repository.HealthScheduleRepository;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.goal.medicineschedule.repository.MedicineScheduleRepository;
import com.widyu.goal.walk.repository.WalkRepository;
import com.widyu.heart.repository.HeartRateResultRepository;
import com.widyu.home.dto.response.GuardianHomeCardsResponse;
import com.widyu.location.access.LocationAccessPath;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GuardianHomeService 홈 카드 위치 열람 기록 단위 테스트")
class GuardianHomeServiceTest {

    private static final Long GUARDIAN_ID = 1L;
    private static final Long SENIOR_ID = 2L;

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

    @InjectMocks
    private GuardianHomeService guardianHomeService;

    @Test
    @DisplayName("보호자가 홈 카드를 열면 외출 상태를 읽은 시니어에 대해 HOME_OUTING 열람 기록을 남긴다")
    void 홈_카드_조회는_HOME_OUTING_기록을_남긴다() {
        // given
        givenEmptyHomeCards();

        // when
        GuardianHomeCardsResponse response = guardianHomeService.getHomeCards(SENIOR_ID);

        // then
        assertThat(response).isNotNull();
        then(locationAccessLogService).should()
                .record(GUARDIAN_ID, SENIOR_ID, LocationAccessPath.HOME_OUTING);
    }

    @Test
    @DisplayName("열람 기록이 예외를 던져도 홈 카드 응답은 그대로 돌려준다")
    void 열람_기록이_실패해도_홈_카드를_돌려준다() {
        // given
        givenEmptyHomeCards();
        willThrow(new IllegalStateException("DB 장애"))
                .given(locationAccessLogService)
                .record(GUARDIAN_ID, SENIOR_ID, LocationAccessPath.HOME_OUTING);

        // when
        GuardianHomeCardsResponse response = guardianHomeService.getHomeCards(SENIOR_ID);

        // then
        assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("접근 권한이 없어 홈 카드 조회가 막히면 열람 기록을 남기지 않는다")
    void 권한이_없으면_열람_기록을_남기지_않는다() {
        // given
        givenEmptyHomeCards();
        given(familyMembershipRepository.existsByFamilyIdAndGuardianId(any(), anyLong()))
                .willReturn(false);

        // when & then
        assertThatThrownBy(() -> guardianHomeService.getHomeCards(SENIOR_ID))
                .isInstanceOf(BusinessException.class);
        then(locationAccessLogService).shouldHaveNoInteractions();
    }

    private void givenEmptyHomeCards() {
        Member guardian = member(GUARDIAN_ID, MemberType.GUARDIAN, "김보호");
        Member senior = member(SENIOR_ID, MemberType.SENIOR, "부모님");
        SeniorProfile seniorProfile = SeniorProfile.createSeniorProfile(
                senior, Family.createFamily("ABC123"), "서울시", "INV1234", LocalDate.of(1950, 1, 1));
        ReflectionTestUtils.setField(seniorProfile, "id", 10L);
        ReflectionTestUtils.setField(senior, "seniorProfile", seniorProfile);

        given(memberUtil.getCurrentMember()).willReturn(guardian);
        given(memberRepository.findById(SENIOR_ID)).willReturn(Optional.of(senior));
        given(seniorProfileRepository.findByMemberId(SENIOR_ID)).willReturn(Optional.of(seniorProfile));
        given(familyMembershipRepository.existsByFamilyIdAndGuardianId(any(), anyLong())).willReturn(true);
        given(realtimeLocationService.getOutingStatus(SENIOR_ID)).willReturn(Boolean.TRUE);
        given(heartRateResultRepository.findByMemberId(SENIOR_ID)).willReturn(Optional.empty());
        given(medicineScheduleRepository.findEffectiveByMemberAndDateWithDetails(
                any(Member.class), any(Status.class), any(LocalDate.class))).willReturn(List.of());
        given(albumRecommendationService.recommendAlbums(any(Member.class), any(LocalDate.class)))
                .willReturn(List.of());
        given(healthScheduleRepository.findFirstByMemberIdAndScheduledAtAfterOrderByScheduledAtAsc(
                anyLong(), any(LocalDateTime.class))).willReturn(Optional.empty());
        given(walkRepository.findByMemberAndWalkDate(any(Member.class), any(LocalDate.class)))
                .willReturn(Optional.empty());
    }

    private Member member(Long id, MemberType type, String name) {
        Member member = Member.createMember(type, name, "01011112222");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }
}
