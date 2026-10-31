package com.widyu.album.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.widyu.album.Album;
import com.widyu.album.dto.request.AlbumFeedRequest;
import com.widyu.album.dto.response.AlbumMediaResponse;
import com.widyu.album.repository.AlbumUnlockRepository;
import com.widyu.global.dto.CursorPage;
import com.widyu.member.MemberType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import com.widyu.album.repository.AlbumLikeRepository;
import com.widyu.album.repository.AlbumRepository;
import com.widyu.album.repository.AlbumViewRepository;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import com.widyu.member.application.FamilyAccessService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("AlbumFeedService 단위 테스트")
class AlbumFeedServiceTest {

    @Mock private AlbumRepository albumRepository;
    @Mock private AlbumLikeRepository albumLikeRepository;
    @Mock private AlbumViewRepository albumViewRepository;
    @Mock private MemberUtil memberUtil;
    @Mock private FamilyAccessService familyAccessService;
    @Mock private AlbumUnlockRepository albumUnlockRepository;

    @InjectMocks
    private AlbumFeedService albumFeedService;

    @Test
    @DisplayName("앨범 피드는 현재 사용자의 가족 구성원 ID로 조회한다")
    void 앨범_피드는_가족_구성원_ID로_조회한다() {
        // given
        Member currentMember = org.mockito.Mockito.mock(Member.class);
        List<Long> familyMemberIds = List.of(1L, 2L, 3L);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);
        given(familyAccessService.getFamilyMemberIds(currentMember)).willReturn(familyMemberIds);
        given(albumRepository.findLatestAlbumIdsByMemberIds(eq(familyMemberIds), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        // when
        albumFeedService.getAlbumFeed(AlbumFeedRequest.from(null, null));

        // then
        then(albumRepository).should().findLatestAlbumIdsByMemberIds(eq(familyMemberIds), any(Pageable.class));
    }

    @Test
    @DisplayName("미디어 피드는 현재 사용자의 가족 구성원 ID로 조회한다")
    void 미디어_피드는_가족_구성원_ID로_조회한다() {
        // given
        Member currentMember = org.mockito.Mockito.mock(Member.class);
        List<Long> familyMemberIds = List.of(1L, 2L, 3L);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);
        given(familyAccessService.getFamilyMemberIds(currentMember)).willReturn(familyMemberIds);
        given(albumRepository.findLatestAlbumIdsByMemberIds(eq(familyMemberIds), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        // when
        albumFeedService.getMediaFeed(null, null);

        // then
        then(albumRepository).should().findLatestAlbumIdsByMemberIds(eq(familyMemberIds), any(Pageable.class));
    }

    @Test
    @DisplayName("시니어가 해금하지 않은 보호자 앨범을 미디어 피드로 보면 원본 URL 없이 썸네일과 잠금 여부를 담는다")
    void 해금하지_않은_보호자_앨범의_미디어_피드는_원본_URL을_담지_않는다() {
        // given
        Member senior = Member.createMember(MemberType.SENIOR, "부모님", "01011112222");
        Member guardian = Member.createMember(MemberType.GUARDIAN, "보호자", "01099999999");
        Album album = Album.createAlbumWithMetadata(guardian, "영상",
                new ArrayList<>(List.of("https://cdn/v.mp4")),
                new ArrayList<>(List.of("https://cdn/v_thumbnail.jpg")),
                new ArrayList<>(List.of(12)));
        ReflectionTestUtils.setField(album, "id", 30L);
        ReflectionTestUtils.setField(album, "createdAt", LocalDateTime.of(2026, 10, 31, 9, 0));
        List<Long> familyMemberIds = List.of(1L, 2L);

        given(memberUtil.getCurrentMember()).willReturn(senior);
        given(familyAccessService.getFamilyMemberIds(senior)).willReturn(familyMemberIds);
        given(albumRepository.findLatestAlbumIdsByMemberIds(eq(familyMemberIds), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(30L)));
        given(albumRepository.findAlbumsWithCollectionsByIds(List.of(30L))).willReturn(new ArrayList<>(List.of(album)));
        given(albumUnlockRepository.findUnlockedAlbumIdsByMemberAndAlbumIds(senior, List.of(30L))).willReturn(List.of());

        // when
        CursorPage<AlbumMediaResponse> page = albumFeedService.getMediaFeed(null, null);

        // then
        AlbumMediaResponse media = page.items().getFirst();
        assertThat(media.isUnlocked()).isFalse();
        assertThat(media.thumbnailUrl()).isEqualTo("https://cdn/v_thumbnail.jpg");
    }
}
