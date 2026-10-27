package com.widyu.album.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.widyu.album.Album;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.application.FamilyAccessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AlbumPermissionService 단위 테스트")
class AlbumPermissionServiceTest {

    @Mock
    private AlbumUnlockService albumUnlockService;

    @Mock
    private FamilyAccessService familyAccessService;

    @InjectMocks
    private AlbumPermissionService albumPermissionService;

    @Test
    @DisplayName("앨범 작성자 본인이면 가족 검사 없이 접근을 허용한다")
    void 앨범_작성자_본인은_가족_검사_없이_허용() {
        // given
        Member owner = mock(Member.class);
        given(owner.getId()).willReturn(1L);

        Album album = mock(Album.class);
        given(album.getMember()).willReturn(owner);

        // when & then
        assertThatNoException().isThrownBy(() -> albumPermissionService.checkFamilyAccess(album, owner));
        then(familyAccessService).should(never()).verifySameFamily(any(), any());
    }

    @Test
    @DisplayName("가족 외 앨범에 접근하면 FORBIDDEN 예외를 던진다")
    void 가족_외_앨범_접근_시_예외가_발생한다() {
        // given
        Member guardian = mock(Member.class);
        given(guardian.getId()).willReturn(2L);

        Member owner = mock(Member.class);
        given(owner.getId()).willReturn(1L);

        Album album = mock(Album.class);
        given(album.getMember()).willReturn(owner);
        willThrow(new BusinessException(ErrorCode.FORBIDDEN)).given(familyAccessService)
                .verifySameFamily(guardian, owner);

        // when & then
        assertThatThrownBy(() -> albumPermissionService.checkFamilyAccess(album, guardian))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("보호자(GUARDIAN)가 조회하면 해금 없이 잠금 해제 상태다")
    void 보호자는_해금_없이_잠금_해제_상태() {
        // given
        Member guardian = mock(Member.class);
        given(guardian.getType()).willReturn(MemberType.GUARDIAN);

        // when & then
        assertThat(albumPermissionService.isUnlockedFor(mock(Album.class), guardian)).isTrue();
    }

    @Test
    @DisplayName("시니어가 올린 앨범은 다른 시니어에게 해금 없이 잠금 해제 상태다")
    void 시니어가_올린_앨범은_해금_없이_잠금_해제_상태() {
        // given
        Member senior = mock(Member.class);
        given(senior.getType()).willReturn(MemberType.SENIOR);

        Album album = mock(Album.class);
        given(album.requiresUnlock()).willReturn(false);

        // when & then
        assertThat(albumPermissionService.isUnlockedFor(album, senior)).isTrue();
    }

    @Test
    @DisplayName("보호자가 올린 앨범은 시니어의 해금 기록 유무가 잠금 해제 상태가 된다")
    void 보호자가_올린_앨범은_해금_기록대로_판정() {
        // given
        Member senior = mock(Member.class);
        given(senior.getType()).willReturn(MemberType.SENIOR);

        Album unlocked = mock(Album.class);
        given(unlocked.requiresUnlock()).willReturn(true);
        given(albumUnlockService.isAlbumUnlocked(unlocked, senior)).willReturn(true);

        Album locked = mock(Album.class);
        given(locked.requiresUnlock()).willReturn(true);
        given(albumUnlockService.isAlbumUnlocked(locked, senior)).willReturn(false);

        // when & then
        assertThat(albumPermissionService.isUnlockedFor(unlocked, senior)).isTrue();
        assertThat(albumPermissionService.isUnlockedFor(locked, senior)).isFalse();
    }
}
