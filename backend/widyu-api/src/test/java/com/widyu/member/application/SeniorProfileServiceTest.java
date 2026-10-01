package com.widyu.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;

import com.widyu.album.repository.AlbumUnlockRepository;
import com.widyu.album.dto.response.UnlockedAlbumIdsResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.fcm.event.point.dto.PointChangedEvent;
import com.widyu.member.Family;
import com.widyu.member.application.FamilyAccessService;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.PointHistory;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.PointHistoryRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("SeniorProfileService 예외 처리 단위 테스트")
class SeniorProfileServiceTest {

    @Mock private MemberUtil memberUtil;
    @Mock private AlbumUnlockRepository albumUnlockRepository;
    @Mock private FamilyAccessService familyAccessService;
    @Mock private SeniorProfileRepository seniorProfileRepository;
    @Mock private PointHistoryRepository pointHistoryRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private SeniorProfileService seniorProfileService;

    @Test
    @DisplayName("보호자가 포인트 조회 시 FORBIDDEN 예외를 던진다")
    void 보호자가_포인트_조회_시_예외가_발생한다() {
        // given
        given(memberUtil.getCurrentMember()).willReturn(member(1L, MemberType.GUARDIAN));

        // when & then
        assertThatThrownBy(seniorProfileService::getLeftPoints)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN)
                .hasMessageContaining("시니어 회원만 접근할 수 있습니다.");
    }

    @Test
    @DisplayName("해금된 앨범 목록을 조회하면 같은 가족 시니어의 앨범도 포함한다")
    void 해금된_앨범_목록에_같은_가족_시니어_앨범을_포함한다() {
        // given
        Member currentMember = member(1L, MemberType.SENIOR);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);
        given(albumUnlockRepository.findUnlockedAlbumIdsByMember(currentMember))
                .willReturn(List.of(10L));
        given(familyAccessService.getFamilyMemberIds(currentMember)).willReturn(List.of(1L, 2L, 3L));
        given(albumUnlockRepository.findActiveAlbumIdsByMemberIdsAndMemberType(
                List.of(1L, 2L, 3L), MemberType.SENIOR))
                .willReturn(List.of(20L, 10L));

        // when
        UnlockedAlbumIdsResponse response = seniorProfileService.getUnlockedAlbums();

        // then
        assertThat(response.albumIds()).containsExactly(10L, 20L);
    }

    @Test
    @DisplayName("포인트 적립 대상 시니어 프로필이 없으면 SENIOR_PROFILE_NOT_FOUND 예외를 던진다")
    void 포인트_적립_대상_프로필이_없으면_예외가_발생한다() {
        // given
        given(seniorProfileRepository.findByMemberId(1L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> seniorProfileService.addPointsToMember(1L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SENIOR_PROFILE_NOT_FOUND)
                .hasMessageContaining("시니어 프로필을 찾을 수 없습니다.");
        then(pointHistoryRepository).should(never()).save(any(PointHistory.class));
    }

    @Test
    @DisplayName("차감 포인트가 부족하면 BAD_REQUEST 예외를 던지고 이력을 저장하지 않는다")
    void 차감_포인트가_부족하면_예외가_발생한다() {
        // given
        SeniorProfile seniorProfile = SeniorProfile.createSeniorProfile(
                member(1L, MemberType.SENIOR), Family.createFamily("ABC123"), "서울시", "INV1234", LocalDate.of(1950, 1, 1));
        ReflectionTestUtils.setField(seniorProfile, "points", 10L);
        given(seniorProfileRepository.findByMemberId(1L)).willReturn(Optional.of(seniorProfile));

        // when & then
        assertThatThrownBy(() -> seniorProfileService.deductPointsFromMember(1L, 50L, "취소"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BAD_REQUEST)
                .hasMessageContaining("결제 취소에 필요한 포인트가 부족합니다.");
        then(pointHistoryRepository).should(never()).save(any(PointHistory.class));
    }

    @Test
    @DisplayName("일반 포인트를 적립하면 내역과 P01 사건을 함께 만든다")
    void 일반_포인트를_적립하면_내역과_P01_사건을_만든다() {
        // given
        SeniorProfile profile = profile();
        given(seniorProfileRepository.findByMemberId(1L)).willReturn(Optional.of(profile));
        savedHistoryHasId(9L);
        ArgumentCaptor<PointChangedEvent> event = ArgumentCaptor.forClass(PointChangedEvent.class);

        // when
        seniorProfileService.addPointsToMember(1L, 10L, "약 복용 인증", "MEDICATION_PROOF:5");

        // then
        assertThat(profile.getPoints()).isEqualTo(110L);
        then(eventPublisher).should().publishEvent(event.capture());
        assertThat(event.getValue().eventId()).isEqualTo("POINT:E:9");
        assertThat(event.getValue().description()).isEqualTo("약 복용 인증");
    }

    @Test
    @DisplayName("목표 보상 포인트를 적립하면 P01 사건을 만들지 않는다")
    void 목표_보상_포인트를_적립하면_P01_사건을_만들지_않는다() {
        // given
        SeniorProfile profile = profile();
        given(seniorProfileRepository.findByMemberId(1L)).willReturn(Optional.of(profile));

        // when
        seniorProfileService.addGoalRewardPoints(1L, 25L, "걷기 목표 달성", "WALK_REWARD:7");

        // then
        assertThat(profile.getPoints()).isEqualTo(125L);
        then(pointHistoryRepository).should().save(any(PointHistory.class));
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("포인트를 사용하면 내역과 P02 사건을 함께 만든다")
    void 포인트를_사용하면_내역과_P02_사건을_만든다() {
        // given
        SeniorProfile profile = profile();
        given(seniorProfileRepository.findByMemberId(1L)).willReturn(Optional.of(profile));
        savedHistoryHasId(10L);
        ArgumentCaptor<PointChangedEvent> event = ArgumentCaptor.forClass(PointChangedEvent.class);

        // when
        seniorProfileService.deductPointsFromMember(1L, 50L, "앨범 해금");

        // then
        assertThat(profile.getPoints()).isEqualTo(50L);
        then(eventPublisher).should().publishEvent(event.capture());
        assertThat(event.getValue().eventId()).isEqualTo("POINT:U:10");
        assertThat(event.getValue().description()).isEqualTo("앨범 해금");
    }

    private SeniorProfile profile() {
        return SeniorProfile.createSeniorProfile(member(1L, MemberType.SENIOR),
                Family.createFamily("ABC123"), "서울시", "INV1234", LocalDate.of(1950, 1, 1));
    }

    private void savedHistoryHasId(Long historyId) {
        willAnswer(invocation -> {
            PointHistory history = invocation.getArgument(0);
            ReflectionTestUtils.setField(history, "id", historyId);
            return history;
        }).given(pointHistoryRepository).save(any(PointHistory.class));
    }

    private Member member(Long id, MemberType type) {
        Member member = Member.createMember(type, type.name(), "01011112222");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }
}
