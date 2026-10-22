package com.widyu.album.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.album.Album;
import com.widyu.album.dto.request.AlbumFeedRequest;
import com.widyu.album.dto.response.AlbumFeedResponse;
import com.widyu.album.dto.response.AlbumMediaResponse;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.global.dto.CursorPage;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.application.FamilyAccessService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, AlbumFeedService.class})
@DisplayName("앨범 피드 커서 페이징 통합 테스트")
class AlbumFeedPagingIntegrationTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 10, 1, 10, 0);

    @Autowired private TestEntityManager entityManager;
    @Autowired private AlbumFeedService albumFeedService;
    @MockBean private JPAQueryFactory jpaQueryFactory;
    @MockBean private MemberUtil memberUtil;
    @MockBean private FamilyAccessService familyAccessService;

    @Test
    @DisplayName("앨범 11개를 커서로 넘기면 첫 페이지 10개, 마지막 페이지 1개로 모든 앨범을 한 번씩 반환한다")
    void 앨범_11개를_커서로_넘기면_모든_앨범을_한_번씩_반환한다() {
        // given
        Member guardian = persistGuardianAsCurrentMember();
        List<Long> newestFirstIds = persistAlbums(guardian, 11);

        // when
        CursorPage<AlbumFeedResponse> first = albumFeedService.getAlbumFeed(AlbumFeedRequest.from(null, null));
        CursorPage<AlbumFeedResponse> last = albumFeedService.getAlbumFeed(
                AlbumFeedRequest.from(first.nextCursor(), null));

        // then
        assertThat(albumIds(first)).containsExactlyElementsOf(newestFirstIds.subList(0, 10));
        assertThat(first.hasNext()).isTrue();
        assertThat(albumIds(last)).containsExactly(newestFirstIds.get(10));
        assertThat(last.hasNext()).isFalse();
    }

    @Test
    @DisplayName("날짜 필터 피드도 앨범 11개를 커서로 넘기면 모든 앨범을 한 번씩 반환한다")
    void 날짜_필터_피드도_앨범_11개를_모두_한_번씩_반환한다() {
        // given
        Member guardian = persistGuardianAsCurrentMember();
        List<Long> newestFirstIds = persistAlbums(guardian, 11);
        String date = BASE_TIME.toLocalDate().toString();

        // when
        CursorPage<AlbumFeedResponse> first = albumFeedService.getAlbumFeed(AlbumFeedRequest.from(null, date));
        CursorPage<AlbumFeedResponse> last = albumFeedService.getAlbumFeed(
                AlbumFeedRequest.from(first.nextCursor(), date));

        // then
        assertThat(albumIds(first)).containsExactlyElementsOf(newestFirstIds.subList(0, 10));
        assertThat(first.hasNext()).isTrue();
        assertThat(albumIds(last)).containsExactly(newestFirstIds.get(10));
        assertThat(last.hasNext()).isFalse();
    }

    @Test
    @DisplayName("미디어 피드는 앨범 11개를 첫 페이지 앨범 10개, 마지막 페이지 1개로 나눠 반환한다")
    void 미디어_피드는_앨범_11개를_10개와_1개로_나눠_반환한다() {
        // given
        Member guardian = persistGuardianAsCurrentMember();
        List<Long> newestFirstIds = persistAlbums(guardian, 11);

        // when
        CursorPage<AlbumMediaResponse> first = albumFeedService.getMediaFeed(null, null);
        AlbumFeedRequest cursor = AlbumFeedRequest.from(first.nextCursor(), null);
        CursorPage<AlbumMediaResponse> last = albumFeedService.getMediaFeed(
                cursor.lastCreatedAt(), cursor.lastAlbumId());

        // then
        assertThat(postIds(first)).containsExactlyElementsOf(newestFirstIds.subList(0, 10));
        assertThat(first.hasNext()).isTrue();
        assertThat(postIds(last)).containsExactly(newestFirstIds.get(10));
        assertThat(last.hasNext()).isFalse();
    }

    private Member persistGuardianAsCurrentMember() {
        Member guardian = entityManager.persist(Member.createMember(MemberType.GUARDIAN, "보호자", "01011112222"));
        given(memberUtil.getCurrentMember()).willReturn(guardian);
        given(familyAccessService.getFamilyMemberIds(guardian)).willReturn(List.of(guardian.getId()));
        return guardian;
    }

    // 생성 시각을 1분 간격으로 고정해 커서 비교가 실행 시각에 좌우되지 않게 한다. 최신순 ID를 돌려준다.
    private List<Long> persistAlbums(Member writer, int count) {
        List<Long> newestFirstIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String photoUrl = "https://cdn.test/photo-" + i + ".jpg";
            Album album = entityManager.persistAndFlush(Album.createAlbumWithMetadata(
                    writer, "앨범 " + i, List.of(photoUrl), List.of(photoUrl), List.of()));
            entityManager.getEntityManager()
                    .createNativeQuery("UPDATE album SET created_at = ?1 WHERE album_id = ?2")
                    .setParameter(1, BASE_TIME.plusMinutes(i))
                    .setParameter(2, album.getId())
                    .executeUpdate();
            newestFirstIds.addFirst(album.getId());
        }
        entityManager.clear();
        return newestFirstIds;
    }

    private List<Long> albumIds(CursorPage<AlbumFeedResponse> page) {
        return page.items().stream().map(AlbumFeedResponse::albumId).toList();
    }

    private List<Long> postIds(CursorPage<AlbumMediaResponse> page) {
        return page.items().stream().map(AlbumMediaResponse::postId).toList();
    }
}
