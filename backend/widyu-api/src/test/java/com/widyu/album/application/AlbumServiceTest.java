package com.widyu.album.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.widyu.album.Album;
import com.widyu.album.AlbumComment;
import com.widyu.album.dto.response.AlbumDetailResponse;
import com.widyu.album.repository.AlbumCommentRepository;
import com.widyu.album.repository.AlbumRepository;
import com.widyu.fcm.event.album.dto.AlbumCreatedEvent;
import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import java.time.LocalDateTime;
import java.util.List;
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
@DisplayName("AlbumService 단위 테스트")
class AlbumServiceTest {

    @Mock private AlbumRepository albumRepository;
    @Mock private AlbumCommentRepository albumCommentRepository;
    @Mock private AlbumViewService albumViewService;
    @Mock private AlbumPermissionService albumPermissionService;
    @Mock private MemberUtil memberUtil;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private AlbumService albumService;

    @Test
    @DisplayName("영상이 없는 앨범 저장 시 ACTIVE 상태로 저장되고 AlbumCreatedEvent가 발행된다")
    void 영상_없는_앨범_저장_시_ACTIVE로_저장되고_이벤트_발행() {
        // given
        Member member = mock(Member.class);
        given(member.getId()).willReturn(1L);
        Album savedAlbum = mock(Album.class);
        given(savedAlbum.getId()).willReturn(10L);
        given(albumRepository.save(any(Album.class))).willReturn(savedAlbum);

        // when
        Long albumId = albumService.saveAlbum(member, "오늘 하루", List.of("photo.jpg"), List.of(), List.of(), false);

        // then
        assertThat(albumId).isEqualTo(10L);
        verify(eventPublisher).publishEvent(any(AlbumCreatedEvent.class));
    }

    @Test
    @DisplayName("영상이 있는 앨범 저장 시 PROCESSING 상태로 저장되고 이벤트가 발행되지 않는다")
    void 영상_있는_앨범_저장_시_PROCESSING으로_저장되고_이벤트_미발행() {
        // given
        Member member = mock(Member.class);
        Album savedAlbum = mock(Album.class);
        given(savedAlbum.getId()).willReturn(10L);
        given(albumRepository.save(any(Album.class))).willReturn(savedAlbum);

        // when
        Long albumId = albumService.saveAlbum(member, "영상 앨범", List.of("video.mp4"), List.of(), List.of(), true);

        // then
        assertThat(albumId).isEqualTo(10L);
        verify(eventPublisher, never()).publishEvent(any(AlbumCreatedEvent.class));
    }

    @Test
    @DisplayName("본인 앨범 수정 시 앨범 내용이 변경된다")
    void 본인_앨범_수정_시_내용이_변경된다() {
        // given
        Member currentMember = mock(Member.class);
        given(currentMember.getId()).willReturn(1L);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);

        Member albumOwner = mock(Member.class);
        given(albumOwner.getId()).willReturn(1L);

        Album album = mock(Album.class);
        given(album.getMember()).willReturn(albumOwner);
        given(albumRepository.findByIdAndStatus(1L, Status.ACTIVE)).willReturn(Optional.of(album));

        // when
        albumService.updateAlbum(1L, new com.widyu.album.dto.request.AlbumUpdateRequest("수정된 내용"));

        // then
        verify(album).updateContent("수정된 내용");
    }

    @Test
    @DisplayName("타인 앨범 수정 시 BusinessException을 던진다")
    void 타인_앨범_수정_시_예외가_발생한다() {
        // given
        Member currentMember = mock(Member.class);
        given(currentMember.getId()).willReturn(1L);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);

        Member albumOwner = mock(Member.class);
        given(albumOwner.getId()).willReturn(2L);

        Album album = mock(Album.class);
        given(album.getMember()).willReturn(albumOwner);
        given(albumRepository.findByIdAndStatus(1L, Status.ACTIVE)).willReturn(Optional.of(album));

        // when & then
        assertThatThrownBy(() -> albumService.updateAlbum(1L, new com.widyu.album.dto.request.AlbumUpdateRequest("수정")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("존재하지 않는 앨범 수정 시 ALBUM_NOT_FOUND 예외를 던진다")
    void 존재하지_않는_앨범_수정_시_예외가_발생한다() {
        // given
        Member currentMember = mock(Member.class);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);
        given(albumRepository.findByIdAndStatus(999L, Status.ACTIVE)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> albumService.updateAlbum(999L, new com.widyu.album.dto.request.AlbumUpdateRequest("수정")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALBUM_NOT_FOUND);
    }

    @Test
    @DisplayName("존재하지 않는 앨범 삭제 시 ALBUM_NOT_FOUND 예외를 던진다")
    void 존재하지_않는_앨범_삭제_시_예외가_발생한다() {
        // given
        Member currentMember = mock(Member.class);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);
        given(albumRepository.findByIdAndStatus(999L, Status.ACTIVE)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> albumService.deleteAlbum(999L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALBUM_NOT_FOUND);
    }

    @Test
    @DisplayName("본인 앨범 삭제 시 상태가 DELETED로 변경된다")
    void 본인_앨범_삭제_시_상태가_DELETED로_변경된다() {
        // given
        Member currentMember = mock(Member.class);
        given(currentMember.getId()).willReturn(1L);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);

        Member albumOwner = mock(Member.class);
        given(albumOwner.getId()).willReturn(1L);

        Album album = mock(Album.class);
        given(album.getMember()).willReturn(albumOwner);
        given(albumRepository.findByIdAndStatus(1L, Status.ACTIVE)).willReturn(Optional.of(album));

        // when
        albumService.deleteAlbum(1L);

        // then
        verify(album).delete();
    }

    @Test
    @DisplayName("타인 앨범 삭제 시 BusinessException을 던진다")
    void 타인_앨범_삭제_시_예외가_발생한다() {
        // given
        Member currentMember = mock(Member.class);
        given(currentMember.getId()).willReturn(1L);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);

        Member albumOwner = mock(Member.class);
        given(albumOwner.getId()).willReturn(2L);

        Album album = mock(Album.class);
        given(album.getMember()).willReturn(albumOwner);
        given(albumRepository.findByIdAndStatus(1L, Status.ACTIVE)).willReturn(Optional.of(album));

        // when & then
        assertThatThrownBy(() -> albumService.deleteAlbum(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("앨범 상세 조회 시 본인 앨범이면 조회 기록을 저장하지 않는다")
    void 앨범_상세_조회_시_본인_앨범이면_조회기록을_저장하지_않는다() {
        // given
        Long memberId = 1L;
        Member currentMember = mock(Member.class);
        given(currentMember.getId()).willReturn(memberId);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);

        Member albumOwner = mock(Member.class);
        given(albumOwner.getId()).willReturn(memberId);
        given(albumOwner.getName()).willReturn("시니어");

        Album album = mock(Album.class);
        given(album.getId()).willReturn(10L);
        given(album.getMember()).willReturn(albumOwner);
        given(album.getMediaUrls()).willReturn(List.of());
        given(album.getLikeCount()).willReturn(0);
        given(album.getCommentCount()).willReturn(0);
        given(album.getViewCount()).willReturn(0);
        given(album.getCreatedAt()).willReturn(LocalDateTime.now());

        given(albumRepository.findByIdAndStatusWithCollections(10L, Status.ACTIVE)).willReturn(Optional.of(album));
        given(albumCommentRepository.findTopLevelCommentsByAlbumAndStatus(album, Status.ACTIVE)).willReturn(List.of());
        given(albumViewService.getRecentViewers(album, 3)).willReturn(List.of());
        given(albumPermissionService.isUnlockedFor(album, currentMember)).willReturn(true);

        // when
        albumService.getAlbumDetail(10L);

        // then
        verify(albumViewService, never()).recordView(any(), any());
    }

    @Test
    @DisplayName("앨범 상세 조회 시 타인 앨범이면 조회 기록이 저장된다")
    void 앨범_상세_조회_시_타인_앨범이면_조회기록이_저장된다() {
        // given
        Member currentMember = mock(Member.class);
        given(currentMember.getId()).willReturn(1L);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);

        Member albumOwner = mock(Member.class);
        given(albumOwner.getId()).willReturn(2L);
        given(albumOwner.getName()).willReturn("시니어");

        Album album = mock(Album.class);
        given(album.getId()).willReturn(10L);
        given(album.getMember()).willReturn(albumOwner);
        given(album.getMediaUrls()).willReturn(List.of());
        given(album.getLikeCount()).willReturn(0);
        given(album.getCommentCount()).willReturn(0);
        given(album.getViewCount()).willReturn(0);
        given(album.getCreatedAt()).willReturn(LocalDateTime.now());

        given(albumRepository.findByIdAndStatusWithCollections(10L, Status.ACTIVE)).willReturn(Optional.of(album));
        given(albumCommentRepository.findTopLevelCommentsByAlbumAndStatus(album, Status.ACTIVE)).willReturn(List.of());
        given(albumViewService.getRecentViewers(album, 3)).willReturn(List.of());
        given(albumPermissionService.isUnlockedFor(album, currentMember)).willReturn(true);

        // when
        albumService.getAlbumDetail(10L);

        // then
        verify(albumViewService).recordView(album, currentMember);
    }

    @Test
    @DisplayName("해금하지 않은 앨범 상세를 조회하면 원본 URL 없이 썸네일과 해금 가격, 잠김 상태를 응답하고 조회 기록은 남기지 않는다")
    void 미해금_앨범_상세_조회_시_가격을_응답하고_조회기록을_남기지_않는다() {
        // given
        Member currentMember = mock(Member.class);
        given(currentMember.getId()).willReturn(1L);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);

        Member albumOwner = mock(Member.class);
        given(albumOwner.getId()).willReturn(2L);
        given(albumOwner.getName()).willReturn("보호자");

        Album album = mock(Album.class);
        given(album.getId()).willReturn(10L);
        given(album.getMember()).willReturn(albumOwner);
        given(album.getThumbnailUrls()).willReturn(List.of("https://cdn/v_thumbnail.jpg"));
        given(album.getLikeCount()).willReturn(0);
        given(album.getCommentCount()).willReturn(0);
        given(album.getViewCount()).willReturn(0);
        given(album.getCreatedAt()).willReturn(LocalDateTime.now());

        given(albumRepository.findByIdAndStatusWithCollections(10L, Status.ACTIVE)).willReturn(Optional.of(album));
        given(albumCommentRepository.findTopLevelCommentsByAlbumAndStatus(album, Status.ACTIVE)).willReturn(List.of());
        given(albumViewService.getRecentViewers(album, 3)).willReturn(List.of());
        given(albumPermissionService.isUnlockedFor(album, currentMember)).willReturn(false);

        // when
        AlbumDetailResponse response = albumService.getAlbumDetail(10L);

        // then
        assertThat(response.isUnlocked()).isFalse();
        assertThat(response.price()).isEqualTo(Album.UNLOCK_PRICE);
        assertThat(response.mediaUrls()).isEmpty();
        assertThat(response.thumbnailUrls()).containsExactly("https://cdn/v_thumbnail.jpg");
        verify(albumViewService, never()).recordView(any(), any());
    }

    @Test
    @DisplayName("존재하지 않는 앨범 상세 조회 시 BusinessException을 던진다")
    void 존재하지_않는_앨범_상세_조회_시_예외가_발생한다() {
        // given
        Member currentMember = mock(Member.class);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);
        given(albumRepository.findByIdAndStatusWithCollections(999L, Status.ACTIVE)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> albumService.getAlbumDetail(999L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALBUM_NOT_FOUND);
    }
}
