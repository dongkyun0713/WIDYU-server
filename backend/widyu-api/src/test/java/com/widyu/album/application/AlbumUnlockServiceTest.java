package com.widyu.album.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.widyu.album.Album;
import com.widyu.album.AlbumUnlock;
import com.widyu.album.dto.response.AlbumUnlockResponse;
import com.widyu.album.repository.AlbumRepository;
import com.widyu.album.repository.AlbumUnlockRepository;
import com.widyu.fcm.event.album.dto.AlbumUnlockedEvent;
import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.application.FamilyAccessService;
import com.widyu.member.application.SeniorProfileService;
import com.widyu.member.repository.SeniorProfileRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("AlbumUnlockService 단위 테스트")
class AlbumUnlockServiceTest {

    @Mock private AlbumUnlockRepository albumUnlockRepository;
    @Mock private AlbumRepository albumRepository;
    @Mock private MemberUtil memberUtil;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SeniorProfileRepository seniorProfileRepository;
    @Mock private FamilyAccessService familyAccessService;
    @Mock private SeniorProfileService seniorProfileService;

    @InjectMocks
    private AlbumUnlockService albumUnlockService;

    @Test
    @DisplayName("포인트가 충분한 시니어가 앨범을 해금하면 포인트가 차감되고 해금 기록이 저장된다")
    void 포인트_충분한_시니어가_해금_시_포인트_차감되고_해금기록_저장() {
        // given
        Member senior = Member.createMember(MemberType.SENIOR, "시니어", "01011112222");
        ReflectionTestUtils.setField(senior, "id", 1L);
        ReflectionTestUtils.setField(senior, "type", MemberType.SENIOR);
        given(memberUtil.getCurrentMember()).willReturn(senior);

        Member albumOwner = mock(Member.class);
        given(albumOwner.getId()).willReturn(2L);

        Album album = mock(Album.class);
        given(album.getMember()).willReturn(albumOwner);
        given(album.getContent()).willReturn("앨범 내용");
        given(albumRepository.findByIdAndStatus(10L, Status.ACTIVE)).willReturn(Optional.of(album));
        given(album.requiresUnlock()).willReturn(true);

        SeniorProfile seniorProfile = mock(SeniorProfile.class);
        given(seniorProfile.hasEnoughPoints(50L)).willReturn(true);
        given(seniorProfileRepository.findByMemberId(1L)).willReturn(Optional.of(seniorProfile));

        given(albumUnlockRepository.existsByAlbumAndMember(album, senior)).willReturn(false);

        AlbumUnlock albumUnlock = AlbumUnlock.createUnlock(album, senior);
        given(albumUnlockRepository.save(any(AlbumUnlock.class))).willReturn(albumUnlock);

        // when
        albumUnlockService.unlockAlbum(10L);

        // then
        verify(seniorProfileService).deductPointsFromMember(1L, 50L, "앨범 해금");
        verify(albumUnlockRepository).save(any(AlbumUnlock.class));
        verify(eventPublisher).publishEvent(any(AlbumUnlockedEvent.class));
    }

    @Test
    @DisplayName("앨범을 해금하면 포인트 차감 후 잔여 포인트가 응답에 담긴다")
    void 앨범_해금_시_차감_후_잔여_포인트를_반환한다() {
        // given
        Member senior = Member.createMember(MemberType.SENIOR, "시니어", "01011112222");
        ReflectionTestUtils.setField(senior, "id", 1L);
        ReflectionTestUtils.setField(senior, "type", MemberType.SENIOR);
        given(memberUtil.getCurrentMember()).willReturn(senior);

        Member albumOwner = mock(Member.class);
        given(albumOwner.getId()).willReturn(2L);

        Album album = mock(Album.class);
        given(album.getMember()).willReturn(albumOwner);
        given(album.getContent()).willReturn("앨범 내용");
        given(albumRepository.findByIdAndStatus(10L, Status.ACTIVE)).willReturn(Optional.of(album));
        given(album.requiresUnlock()).willReturn(true);

        // 가입 시 지급되는 100포인트를 가진 실제 프로필
        SeniorProfile seniorProfile = SeniorProfile.createSeniorProfile(
                senior, null, "서울시 강남구", "ABCDEFG", LocalDate.of(1950, 1, 1));
        given(seniorProfileRepository.findByMemberId(1L)).willReturn(Optional.of(seniorProfile));

        given(albumUnlockRepository.existsByAlbumAndMember(album, senior)).willReturn(false);
        given(albumUnlockRepository.save(any(AlbumUnlock.class)))
                .willReturn(AlbumUnlock.createUnlock(album, senior));
        willAnswer(invocation -> {
            seniorProfile.deductPoints(Album.UNLOCK_PRICE);
            return null;
        }).given(seniorProfileService).deductPointsFromMember(1L, Album.UNLOCK_PRICE, "앨범 해금");

        // when
        AlbumUnlockResponse response = albumUnlockService.unlockAlbum(10L);

        // then
        assertThat(response.remainingPoints()).isEqualTo(100L - Album.UNLOCK_PRICE);
        assertThat(seniorProfile.getPoints()).isEqualTo(100L - Album.UNLOCK_PRICE);
    }

    @Test
    @DisplayName("본인 앨범 해금 시도 시 BusinessException을 던진다")
    void 본인_앨범_해금_시도_시_예외가_발생한다() {
        // given
        Member senior = Member.createMember(MemberType.SENIOR, "시니어", "01011112222");
        ReflectionTestUtils.setField(senior, "id", 1L);
        given(memberUtil.getCurrentMember()).willReturn(senior);

        Member albumOwner = mock(Member.class);
        given(albumOwner.getId()).willReturn(1L);

        Album album = mock(Album.class);
        given(album.getMember()).willReturn(albumOwner);
        given(albumRepository.findByIdAndStatus(10L, Status.ACTIVE)).willReturn(Optional.of(album));

        // when & then
        assertThatThrownBy(() -> albumUnlockService.unlockAlbum(10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALBUM_UNLOCK_SELF_NOT_ALLOWED);
    }

    @Test
    @DisplayName("가디언 타입 회원이 해금 시도 시 ALBUM_UNLOCK_SENIOR_ONLY 예외를 던진다")
    void 가디언_타입_회원이_해금_시도_시_예외가_발생한다() {
        // given
        Member guardian = Member.createMember(MemberType.GUARDIAN, "가디언", "01099999999");
        ReflectionTestUtils.setField(guardian, "id", 1L);
        given(memberUtil.getCurrentMember()).willReturn(guardian);

        Member albumOwner = mock(Member.class);
        given(albumOwner.getId()).willReturn(2L);

        Album album = mock(Album.class);
        given(album.getMember()).willReturn(albumOwner);
        given(albumRepository.findByIdAndStatus(10L, Status.ACTIVE)).willReturn(Optional.of(album));
        given(album.requiresUnlock()).willReturn(true);

        // when & then
        assertThatThrownBy(() -> albumUnlockService.unlockAlbum(10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALBUM_UNLOCK_SENIOR_ONLY);
    }

    @Test
    @DisplayName("시니어 프로필이 없는 경우 SENIOR_PROFILE_NOT_FOUND 예외를 던진다")
    void 시니어_프로필이_없으면_예외가_발생한다() {
        // given
        Member senior = Member.createMember(MemberType.SENIOR, "시니어", "01011112222");
        ReflectionTestUtils.setField(senior, "id", 1L);
        ReflectionTestUtils.setField(senior, "type", MemberType.SENIOR);
        given(memberUtil.getCurrentMember()).willReturn(senior);

        Member albumOwner = mock(Member.class);
        given(albumOwner.getId()).willReturn(2L);

        Album album = mock(Album.class);
        given(album.getMember()).willReturn(albumOwner);
        given(albumRepository.findByIdAndStatus(10L, Status.ACTIVE)).willReturn(Optional.of(album));
        given(album.requiresUnlock()).willReturn(true);

        given(seniorProfileRepository.findByMemberId(1L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> albumUnlockService.unlockAlbum(10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SENIOR_PROFILE_NOT_FOUND);
    }

    @Test
    @DisplayName("이미 해금된 앨범 재해금 시도 시 BusinessException을 던진다")
    void 이미_해금된_앨범_재해금_시도_시_예외가_발생한다() {
        // given
        Member senior = Member.createMember(MemberType.SENIOR, "시니어", "01011112222");
        ReflectionTestUtils.setField(senior, "id", 1L);
        ReflectionTestUtils.setField(senior, "type", MemberType.SENIOR);
        given(memberUtil.getCurrentMember()).willReturn(senior);

        Member albumOwner = mock(Member.class);
        given(albumOwner.getId()).willReturn(2L);

        Album album = mock(Album.class);
        given(album.getMember()).willReturn(albumOwner);
        given(albumRepository.findByIdAndStatus(10L, Status.ACTIVE)).willReturn(Optional.of(album));
        given(album.requiresUnlock()).willReturn(true);

        SeniorProfile seniorProfile = mock(SeniorProfile.class);
        given(seniorProfileRepository.findByMemberId(1L)).willReturn(Optional.of(seniorProfile));
        given(albumUnlockRepository.existsByAlbumAndMember(album, senior)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> albumUnlockService.unlockAlbum(10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALBUM_ALREADY_UNLOCKED);
        verify(seniorProfileService, never()).deductPointsFromMember(any(), any(), any());
    }

    @Test
    @DisplayName("포인트가 부족한 시니어가 해금 시도 시 BusinessException을 던진다")
    void 포인트_부족한_시니어가_해금_시도_시_예외가_발생한다() {
        // given
        Member senior = Member.createMember(MemberType.SENIOR, "시니어", "01011112222");
        ReflectionTestUtils.setField(senior, "id", 1L);
        ReflectionTestUtils.setField(senior, "type", MemberType.SENIOR);
        given(memberUtil.getCurrentMember()).willReturn(senior);

        Member albumOwner = mock(Member.class);
        given(albumOwner.getId()).willReturn(2L);

        Album album = mock(Album.class);
        given(album.getMember()).willReturn(albumOwner);
        given(albumRepository.findByIdAndStatus(10L, Status.ACTIVE)).willReturn(Optional.of(album));
        given(album.requiresUnlock()).willReturn(true);

        SeniorProfile seniorProfile = mock(SeniorProfile.class);
        given(seniorProfileRepository.findByMemberId(1L)).willReturn(Optional.of(seniorProfile));
        given(albumUnlockRepository.existsByAlbumAndMember(album, senior)).willReturn(false);
        given(seniorProfile.hasEnoughPoints(50L)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> albumUnlockService.unlockAlbum(10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALBUM_UNLOCK_INSUFFICIENT_BALANCE);
        verify(seniorProfileService, never()).deductPointsFromMember(any(), any(), any());
    }

    @Test
    @DisplayName("가족 외 앨범 해금 시도 시 FORBIDDEN 예외를 던진다")
    void 가족_외_앨범_해금_시_예외가_발생한다() {
        // given
        Member senior = Member.createMember(MemberType.SENIOR, "시니어", "01011112222");
        ReflectionTestUtils.setField(senior, "id", 1L);
        given(memberUtil.getCurrentMember()).willReturn(senior);

        Member albumOwner = mock(Member.class);

        Album album = mock(Album.class);
        given(album.getMember()).willReturn(albumOwner);
        given(albumRepository.findByIdAndStatus(10L, Status.ACTIVE)).willReturn(Optional.of(album));
        willThrow(new BusinessException(ErrorCode.FORBIDDEN)).given(familyAccessService)
                .verifySameFamily(senior, albumOwner);

        // when & then
        assertThatThrownBy(() -> albumUnlockService.unlockAlbum(10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("시니어가 올린 앨범을 해금하려 하면 포인트가 차감되지 않고 ALBUM_UNLOCK_NOT_REQUIRED 예외가 발생한다")
    void 시니어가_올린_앨범_해금_시도_시_예외가_발생한다() {
        // given
        Member senior = Member.createMember(MemberType.SENIOR, "시니어", "01011112222");
        ReflectionTestUtils.setField(senior, "id", 1L);
        ReflectionTestUtils.setField(senior, "type", MemberType.SENIOR);
        given(memberUtil.getCurrentMember()).willReturn(senior);

        Member albumOwner = mock(Member.class);
        given(albumOwner.getId()).willReturn(2L);

        Album album = mock(Album.class);
        given(album.getMember()).willReturn(albumOwner);
        given(albumRepository.findByIdAndStatus(10L, Status.ACTIVE)).willReturn(Optional.of(album));
        given(album.requiresUnlock()).willReturn(false);

        // when & then
        assertThatThrownBy(() -> albumUnlockService.unlockAlbum(10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALBUM_UNLOCK_NOT_REQUIRED);
        verify(albumUnlockRepository, never()).save(any(AlbumUnlock.class));
        verify(seniorProfileService, never()).deductPointsFromMember(any(), any(), any());
    }
}
