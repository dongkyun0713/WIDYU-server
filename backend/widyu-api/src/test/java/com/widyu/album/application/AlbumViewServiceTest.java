package com.widyu.album.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.widyu.album.Album;
import com.widyu.album.AlbumView;
import com.widyu.album.repository.AlbumRepository;
import com.widyu.album.repository.AlbumViewRepository;
import com.widyu.fcm.event.album.dto.AlbumViewedEvent;
import com.widyu.member.Member;
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
@DisplayName("AlbumViewService 단위 테스트")
class AlbumViewServiceTest {

    @Mock private AlbumViewRepository albumViewRepository;
    @Mock private AlbumRepository albumRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private AlbumViewService albumViewService;

    @Test
    @DisplayName("처음 조회한 앨범이면 조회 기록이 저장되고 조회수가 증가한다")
    void 처음_조회한_앨범이면_조회기록_저장되고_조회수_증가() {
        // given
        Member member = mock(Member.class);
        given(member.getId()).willReturn(1L);

        Album album = mock(Album.class);
        given(album.getId()).willReturn(10L);
        given(albumViewRepository.findByAlbumAndMember(album, member)).willReturn(Optional.empty());

        // when
        albumViewService.recordView(album, member);

        // then
        verify(albumViewRepository).save(any(AlbumView.class));
        verify(albumRepository).incrementViewCount(10L);
        verify(album).incrementViewCount();
        verify(eventPublisher).publishEvent(any(AlbumViewedEvent.class));
    }

    @Test
    @DisplayName("이미 조회한 앨범이면 조회 기록이 저장되지 않고 조회수가 증가하지 않는다")
    void 이미_조회한_앨범이면_조회기록_저장되지_않고_조회수_증가하지_않는다() {
        // given
        Member member = mock(Member.class);
        Album album = mock(Album.class);
        AlbumView existingView = mock(AlbumView.class);
        given(albumViewRepository.findByAlbumAndMember(album, member)).willReturn(Optional.of(existingView));

        // when
        albumViewService.recordView(album, member);

        // then
        verify(albumViewRepository, never()).save(any(AlbumView.class));
        verify(albumRepository, never()).incrementViewCount(any());
        verify(album, never()).incrementViewCount();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("최근 조회자 목록 조회 시 limit 개수만큼 반환한다")
    void 최근_조회자_목록_조회_시_limit_개수만큼_반환한다() {
        // given
        Album album = mock(Album.class);
        Member viewer1 = mock(Member.class);
        Member viewer2 = mock(Member.class);
        Member viewer3 = mock(Member.class);
        given(albumViewRepository.findViewersByAlbum(album, 3)).willReturn(List.of(viewer1, viewer2, viewer3));

        // when
        List<Member> viewers = albumViewService.getRecentViewers(album, 3);

        // then
        assertThat(viewers).hasSize(3);
    }

    @Test
    @DisplayName("조회자가 없는 앨범의 최근 조회자 목록 조회 시 빈 리스트를 반환한다")
    void 조회자가_없는_앨범의_최근_조회자_목록_조회_시_빈_리스트_반환() {
        // given
        Album album = mock(Album.class);
        given(albumViewRepository.findViewersByAlbum(album, 3)).willReturn(List.of());

        // when
        List<Member> viewers = albumViewService.getRecentViewers(album, 3);

        // then
        assertThat(viewers).isEmpty();
    }
}
