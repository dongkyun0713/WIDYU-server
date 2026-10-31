package com.widyu.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.widyu.album.Album;
import com.widyu.album.application.AlbumPermissionService;
import com.widyu.global.error.BusinessException;
import com.widyu.global.util.MemberUtil;
import com.widyu.goal.healthschedule.repository.HealthScheduleRepository;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.goal.medicineschedule.repository.MedicineScheduleRepository;
import com.widyu.goal.walk.repository.WalkRepository;
import com.widyu.heart.repository.HeartRateResultRepository;
import com.widyu.home.application.HomeAlbumRecommendationService;
import com.widyu.home.application.SeniorHomeService;
import com.widyu.home.dto.response.SeniorHomeCardsResponse;
import com.widyu.medicine.MedicationProof;
import com.widyu.medicine.MedicineSchedule;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("SeniorHomeService 단위 테스트")
class SeniorHomeServiceTest {

    @InjectMocks private SeniorHomeService seniorHomeService;

    @Mock private MemberUtil memberUtil;
    @Mock private MedicineScheduleRepository medicineScheduleRepository;
    @Mock private MedicationProofRepository medicationProofRepository;
    @Mock private WalkRepository walkRepository;
    @Mock private HealthScheduleRepository healthScheduleRepository;
    @Mock private HeartRateResultRepository heartRateResultRepository;
    @Mock private HomeAlbumRecommendationService albumRecommendationService;
    @Mock private AlbumPermissionService albumPermissionService;

    @Test
    @DisplayName("보호자가 시니어 홈을 호출하면 403 예외가 발생한다")
    void 보호자가_시니어홈_호출하면_403_예외가_발생한다() {
        // given
        Member guardian = Member.createMember(MemberType.GUARDIAN, "보호자", "01099999999");
        given(memberUtil.getCurrentMember()).willReturn(guardian);

        // when & then
        assertThatThrownBy(() -> seniorHomeService.getHomeCards())
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("약 스케줄이 없으면 medicine은 null을 반환한다")
    void 약_스케줄이_없으면_medicine은_null을_반환한다() {
        // given
        Member senior = Member.createMember(MemberType.SENIOR, "부모님", "01011112222");

        given(memberUtil.getCurrentMember()).willReturn(senior);
        given(medicineScheduleRepository.findEffectiveByMemberAndDateWithDetails(any(), any(), any())).willReturn(List.of());
        given(heartRateResultRepository.findByMemberId(any())).willReturn(Optional.empty());
        given(healthScheduleRepository.findByMemberIdAndWeek(any(), any(), any())).willReturn(List.of());
        given(walkRepository.findByMemberAndWalkDate(any(), any())).willReturn(Optional.empty());
        given(albumRecommendationService.recommendAlbums(any(), any())).willReturn(List.of());

        // when
        SeniorHomeCardsResponse response = seniorHomeService.getHomeCards();

        // then
        assertThat(response.medicine()).isNull();
    }

    @Test
    @DisplayName("오늘 복용한 스케줄은 scheduleStatuses에서 taken이 true다")
    void 오늘_복용한_스케줄은_taken이_true다() {
        // given
        Member senior = Member.createMember(MemberType.SENIOR, "부모님", "01011112222");

        MedicineSchedule schedule = mock(MedicineSchedule.class);
        given(schedule.getId()).willReturn(1L);
        given(schedule.getAlarmTime()).willReturn(LocalTime.of(8, 0));
        given(schedule.getTotalCount()).willReturn(2);

        MedicationProof proof = mock(MedicationProof.class);
        given(proof.getMedicineSchedule()).willReturn(schedule);

        given(memberUtil.getCurrentMember()).willReturn(senior);
        given(medicineScheduleRepository.findEffectiveByMemberAndDateWithDetails(any(), any(), any()))
                .willReturn(List.of(schedule));
        given(medicationProofRepository.findByMemberIdAndDateRange(any(), any(), any()))
                .willReturn(List.of(proof));
        given(heartRateResultRepository.findByMemberId(any())).willReturn(Optional.empty());
        given(healthScheduleRepository.findByMemberIdAndWeek(any(), any(), any())).willReturn(List.of());
        given(walkRepository.findByMemberAndWalkDate(any(), any())).willReturn(Optional.empty());
        given(albumRecommendationService.recommendAlbums(any(), any())).willReturn(List.of());

        // when
        SeniorHomeCardsResponse response = seniorHomeService.getHomeCards();

        // then
        assertThat(response.medicine().scheduleStatuses()).hasSize(1);
        assertThat(response.medicine().scheduleStatuses().getFirst().taken()).isTrue();
    }

    @Test
    @DisplayName("오늘 건강 일정이 없으면 healthSchedule은 null을 반환한다")
    void 오늘_건강일정이_없으면_healthSchedule은_null을_반환한다() {
        // given
        Member senior = Member.createMember(MemberType.SENIOR, "부모님", "01011112222");

        given(memberUtil.getCurrentMember()).willReturn(senior);
        given(medicineScheduleRepository.findEffectiveByMemberAndDateWithDetails(any(), any(), any())).willReturn(List.of());
        given(heartRateResultRepository.findByMemberId(any())).willReturn(Optional.empty());
        given(healthScheduleRepository.findByMemberIdAndWeek(any(), any(), any())).willReturn(List.of());
        given(walkRepository.findByMemberAndWalkDate(any(), any())).willReturn(Optional.empty());
        given(albumRecommendationService.recommendAlbums(any(), any())).willReturn(List.of());

        // when
        SeniorHomeCardsResponse response = seniorHomeService.getHomeCards();

        // then
        assertThat(response.healthSchedule()).isNull();
    }

    @Test
    @DisplayName("걷기 데이터와 기본 목표가 없으면 walk는 null을 반환한다")
    void 걷기_데이터와_기본목표가_없으면_walk는_null을_반환한다() {
        // given
        Member senior = Member.createMember(MemberType.SENIOR, "부모님", "01011112222");

        given(memberUtil.getCurrentMember()).willReturn(senior);
        given(medicineScheduleRepository.findEffectiveByMemberAndDateWithDetails(any(), any(), any())).willReturn(List.of());
        given(heartRateResultRepository.findByMemberId(any())).willReturn(Optional.empty());
        given(healthScheduleRepository.findByMemberIdAndWeek(any(), any(), any())).willReturn(List.of());
        given(walkRepository.findByMemberAndWalkDate(any(), any())).willReturn(Optional.empty());
        given(albumRecommendationService.recommendAlbums(any(), any())).willReturn(List.of());

        // when
        SeniorHomeCardsResponse response = seniorHomeService.getHomeCards();

        // then
        assertThat(response.walk()).isNull();
    }

    @Test
    @DisplayName("가족 앨범이 없으면 albums는 빈 배열을 반환한다")
    void 가족_앨범이_없으면_albums는_빈배열을_반환한다() {
        // given
        Member senior = Member.createMember(MemberType.SENIOR, "부모님", "01011112222");

        given(memberUtil.getCurrentMember()).willReturn(senior);
        given(medicineScheduleRepository.findEffectiveByMemberAndDateWithDetails(any(), any(), any())).willReturn(List.of());
        given(heartRateResultRepository.findByMemberId(any())).willReturn(Optional.empty());
        given(healthScheduleRepository.findByMemberIdAndWeek(any(), any(), any())).willReturn(List.of());
        given(walkRepository.findByMemberAndWalkDate(any(), any())).willReturn(Optional.empty());
        given(albumRecommendationService.recommendAlbums(any(), any())).willReturn(List.of());

        // when
        SeniorHomeCardsResponse response = seniorHomeService.getHomeCards();

        // then
        assertThat(response.albums()).isEmpty();
    }

    @Test
    @DisplayName("잠금 전 보호자 앨범 카드는 썸네일이 없으면 원본 URL 대신 null과 잠금 여부를 담는다")
    void 잠금_전_보호자_앨범_카드는_원본_URL을_담지_않는다() {
        // given
        Member senior = Member.createMember(MemberType.SENIOR, "부모님", "01011112222");
        Member guardian = Member.createMember(MemberType.GUARDIAN, "보호자", "01099999999");
        Album lockedPhotoAlbum = Album.createAlbumWithMetadata(guardian, "사진",
                new ArrayList<>(List.of("https://cdn/p.jpg")), new ArrayList<>(), new ArrayList<>());

        given(memberUtil.getCurrentMember()).willReturn(senior);
        given(medicineScheduleRepository.findEffectiveByMemberAndDateWithDetails(any(), any(), any())).willReturn(List.of());
        given(heartRateResultRepository.findByMemberId(any())).willReturn(Optional.empty());
        given(healthScheduleRepository.findByMemberIdAndWeek(any(), any(), any())).willReturn(List.of());
        given(walkRepository.findByMemberAndWalkDate(any(), any())).willReturn(Optional.empty());
        given(albumRecommendationService.recommendAlbums(any(), any())).willReturn(List.of(lockedPhotoAlbum));
        given(albumPermissionService.isUnlockedFor(lockedPhotoAlbum, senior)).willReturn(false);

        // when
        SeniorHomeCardsResponse response = seniorHomeService.getHomeCards();

        // then
        assertThat(response.albums()).hasSize(1);
        assertThat(response.albums().getFirst().thumbnailUrl()).isNull();
        assertThat(response.albums().getFirst().isUnlocked()).isFalse();
    }
}
