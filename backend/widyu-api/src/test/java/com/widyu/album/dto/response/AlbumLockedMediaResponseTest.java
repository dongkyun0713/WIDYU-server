package com.widyu.album.dto.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.widyu.album.Album;
import com.widyu.album.dto.FamilyAlbumResponse;
import com.widyu.home.dto.response.SeniorHomeCardsResponse;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("잠금 전 앨범 응답의 원본 URL 제외 (#813)")
class AlbumLockedMediaResponseTest {

    private static final String VIDEO_URL = "https://cdn.widyu.shop/albums/videos/1/v.mp4";
    private static final String VIDEO_THUMB = "https://cdn.widyu.shop/albums/thumbnails/1/v_thumbnail.jpg";
    private static final String PHOTO_URL = "https://cdn.widyu.shop/albums/photos/1/p.jpg";

    private static Album videoAndPhotoAlbum() {
        // 상세 응답이 작성자 ID로 본인 여부를 비교하므로 ID가 있는 실제 회원을 쓴다.
        Member author = Member.createMember(MemberType.GUARDIAN, "보호자", "01000000000");
        ReflectionTestUtils.setField(author, "id", 1L);
        Album album = Album.createAlbumWithMetadata(author, "내용",
                new ArrayList<>(List.of(VIDEO_URL, PHOTO_URL)),
                new ArrayList<>(List.of(VIDEO_THUMB, PHOTO_URL)),
                new ArrayList<>(Arrays.asList(12, null)));
        ReflectionTestUtils.setField(album, "id", 10L);
        return album;
    }

    @Test
    @DisplayName("잠금 전 앨범 피드 응답은 원본 URL 없이 썸네일과 잠금 여부를 담는다")
    void 잠금_전_앨범_피드는_원본_URL_없이_썸네일만_담는다() {
        // given
        Album album = videoAndPhotoAlbum();

        // when
        AlbumFeedResponse response = AlbumFeedResponse.from(album, false, false, List.of());

        // then
        assertThat(response.mediaUrls()).isEmpty();
        assertThat(response.thumbnailUrls()).containsExactly(VIDEO_THUMB, PHOTO_URL);
        assertThat(response.isUnlocked()).isFalse();
        assertThat(response.mediaCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("해금한 앨범 피드 응답은 기존처럼 원본 URL과 썸네일을 모두 담는다")
    void 해금한_앨범_피드는_원본_URL을_담는다() {
        // given
        Album album = videoAndPhotoAlbum();

        // when
        AlbumFeedResponse response = AlbumFeedResponse.from(album, false, true, List.of());

        // then
        assertThat(response.mediaUrls()).containsExactly(VIDEO_URL, PHOTO_URL);
        assertThat(response.thumbnailUrls()).containsExactly(VIDEO_THUMB, PHOTO_URL);
        assertThat(response.isUnlocked()).isTrue();
    }

    @Test
    @DisplayName("잠금 전 앨범 상세 응답은 원본 URL 없이 썸네일과 잠금 여부를 담는다")
    void 잠금_전_앨범_상세는_원본_URL_없이_썸네일만_담는다() {
        // given
        Album album = videoAndPhotoAlbum();

        // when
        AlbumDetailResponse response = AlbumDetailResponse.from(album, 99L, List.of(), List.of(), false);

        // then
        assertThat(response.mediaUrls()).isEmpty();
        assertThat(response.thumbnailUrls()).containsExactly(VIDEO_THUMB, PHOTO_URL);
        assertThat(response.isUnlocked()).isFalse();
    }

    @Test
    @DisplayName("해금한 앨범 상세 응답은 원본 URL과 썸네일을 모두 담는다")
    void 해금한_앨범_상세는_원본_URL을_담는다() {
        // given
        Album album = videoAndPhotoAlbum();

        // when
        AlbumDetailResponse response = AlbumDetailResponse.from(album, 99L, List.of(), List.of(), true);

        // then
        assertThat(response.mediaUrls()).containsExactly(VIDEO_URL, PHOTO_URL);
        assertThat(response.thumbnailUrls()).containsExactly(VIDEO_THUMB, PHOTO_URL);
        assertThat(response.isUnlocked()).isTrue();
    }

    @Test
    @DisplayName("잠금 전 미디어 피드 응답은 저장된 썸네일과 잠금 여부를 담는다")
    void 잠금_전_미디어_피드는_저장된_썸네일만_담는다() {
        // given
        Album album = videoAndPhotoAlbum();

        // when
        List<AlbumMediaResponse> responses = AlbumMediaResponse.fromAlbum(album, false);

        // then
        assertThat(responses).extracting(AlbumMediaResponse::thumbnailUrl)
                .containsExactly(VIDEO_THUMB, PHOTO_URL)
                .doesNotContain(VIDEO_URL);
        assertThat(responses).extracting(AlbumMediaResponse::isUnlocked).containsOnly(false);
    }

    @Test
    @DisplayName("잠금 전 미디어 피드는 사진 썸네일이 없으면 원본 URL로 대신하지 않는다")
    void 잠금_전_미디어_피드는_사진_썸네일이_없으면_원본으로_대신하지_않는다() {
        // given
        Album album = Album.createAlbumWithMetadata(mock(Member.class), "내용",
                new ArrayList<>(List.of(PHOTO_URL)), new ArrayList<>(), new ArrayList<>());
        ReflectionTestUtils.setField(album, "id", 11L);

        // when
        List<AlbumMediaResponse> locked = AlbumMediaResponse.fromAlbum(album, false);
        List<AlbumMediaResponse> unlocked = AlbumMediaResponse.fromAlbum(album, true);

        // then
        assertThat(locked.getFirst().thumbnailUrl()).isNull();
        assertThat(unlocked.getFirst().thumbnailUrl()).isEqualTo(PHOTO_URL);
        assertThat(unlocked.getFirst().isUnlocked()).isTrue();
    }

    @Test
    @DisplayName("잠금 전 캘린더 앨범 응답은 원본 URL 없이 썸네일과 잠금 여부를 담는다")
    void 잠금_전_캘린더_앨범은_원본_URL_없이_썸네일만_담는다() {
        // given
        Album album = videoAndPhotoAlbum();

        // when
        FamilyAlbumResponse locked = FamilyAlbumResponse.from(album, List.of(), false);
        FamilyAlbumResponse unlocked = FamilyAlbumResponse.from(album, List.of(), true);

        // then
        assertThat(locked.mediaUrls()).isEmpty();
        assertThat(locked.thumbnailUrls()).containsExactly(VIDEO_THUMB, PHOTO_URL);
        assertThat(locked.isUnlocked()).isFalse();
        assertThat(unlocked.mediaUrls()).containsExactly(VIDEO_URL, PHOTO_URL);
        assertThat(unlocked.isUnlocked()).isTrue();
    }

    @Test
    @DisplayName("잠금 전 시니어 홈 앨범 카드는 썸네일이 없으면 원본 URL 대신 null을 담는다")
    void 잠금_전_시니어_홈_카드는_썸네일이_없으면_null을_담는다() {
        // given
        Album album = Album.createAlbumWithMetadata(mock(Member.class), "내용",
                new ArrayList<>(List.of(PHOTO_URL)), new ArrayList<>(), new ArrayList<>());

        // when
        SeniorHomeCardsResponse.AlbumInfo locked = SeniorHomeCardsResponse.AlbumInfo.from(album, false);
        SeniorHomeCardsResponse.AlbumInfo unlocked = SeniorHomeCardsResponse.AlbumInfo.from(album, true);

        // then
        assertThat(locked.thumbnailUrl()).isNull();
        assertThat(locked.isUnlocked()).isFalse();
        assertThat(unlocked.thumbnailUrl()).isEqualTo(PHOTO_URL);
        assertThat(unlocked.isUnlocked()).isTrue();
    }

    @Test
    @DisplayName("잠금 전 시니어 홈 앨범 카드는 썸네일이 있으면 첫 썸네일을 담는다")
    void 잠금_전_시니어_홈_카드는_첫_썸네일을_담는다() {
        // given
        Album album = videoAndPhotoAlbum();

        // when
        SeniorHomeCardsResponse.AlbumInfo locked = SeniorHomeCardsResponse.AlbumInfo.from(album, false);

        // then
        assertThat(locked.thumbnailUrl()).isEqualTo(VIDEO_THUMB);
        assertThat(locked.isUnlocked()).isFalse();
    }
}
