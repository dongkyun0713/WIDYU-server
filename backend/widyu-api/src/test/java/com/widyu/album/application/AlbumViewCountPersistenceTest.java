package com.widyu.album.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.album.Album;
import com.widyu.album.dto.response.AlbumDetailResponse;
import com.widyu.album.repository.AlbumRepository;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 상세 조회(읽기 전용)와 조회 기록(REQUIRES_NEW)이 각자 커밋해야 결함이 드러나므로 테스트 트랜잭션을 쓰지 않는다.
@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, AlbumService.class, AlbumViewService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("앨범 조회수 DB 반영 테스트")
class AlbumViewCountPersistenceTest {

    @Autowired private AlbumService albumService;
    @Autowired private AlbumRepository albumRepository;
    @Autowired private MemberRepository memberRepository;
    @MockBean private JPAQueryFactory jpaQueryFactory;
    @MockBean private MemberUtil memberUtil;
    @MockBean private AlbumPermissionService albumPermissionService;

    @Test
    @DisplayName("다른 가족 회원이 처음 상세 조회하면 DB의 조회수가 1 늘고 응답에도 반영된다")
    void 다른_가족_회원이_처음_상세_조회하면_DB_조회수가_1_늘어난다() {
        // given
        Member writer = memberRepository.save(Member.createMember(MemberType.GUARDIAN, "작성자", "01011112222"));
        Member viewer = memberRepository.save(Member.createMember(MemberType.SENIOR, "시니어", "01033334444"));
        Album album = albumRepository.save(activeAlbum(writer));
        given(memberUtil.getCurrentMember()).willReturn(viewer);

        try {
            // when
            AlbumDetailResponse response = albumService.getAlbumDetail(album.getId());

            // then
            assertThat(response.viewCount()).isEqualTo(1);
            assertThat(persistedViewCount(album)).isEqualTo(1);
        } finally {
            cleanUp(album, writer, viewer);
        }
    }

    @Test
    @DisplayName("같은 회원이 다시 상세 조회하면 DB의 조회수가 늘지 않는다")
    void 같은_회원이_다시_상세_조회하면_DB_조회수가_늘지_않는다() {
        // given
        Member writer = memberRepository.save(Member.createMember(MemberType.GUARDIAN, "작성자", "01011112222"));
        Member viewer = memberRepository.save(Member.createMember(MemberType.SENIOR, "시니어", "01033334444"));
        Album album = albumRepository.save(activeAlbum(writer));
        given(memberUtil.getCurrentMember()).willReturn(viewer);
        albumService.getAlbumDetail(album.getId());

        try {
            // when
            AlbumDetailResponse response = albumService.getAlbumDetail(album.getId());

            // then
            assertThat(response.viewCount()).isEqualTo(1);
            assertThat(persistedViewCount(album)).isEqualTo(1);
        } finally {
            cleanUp(album, writer, viewer);
        }
    }

    @Test
    @DisplayName("작성자 본인이 상세 조회하면 DB의 조회수가 늘지 않는다")
    void 작성자_본인이_상세_조회하면_DB_조회수가_늘지_않는다() {
        // given
        Member writer = memberRepository.save(Member.createMember(MemberType.GUARDIAN, "작성자", "01011112222"));
        Album album = albumRepository.save(activeAlbum(writer));
        given(memberUtil.getCurrentMember()).willReturn(writer);

        try {
            // when
            albumService.getAlbumDetail(album.getId());

            // then
            assertThat(persistedViewCount(album)).isZero();
        } finally {
            cleanUp(album, writer);
        }
    }

    private Album activeAlbum(Member writer) {
        return Album.createAlbumWithMetadata(writer, "앨범", List.of(), List.of(), List.of());
    }

    private int persistedViewCount(Album album) {
        return albumRepository.findById(album.getId()).orElseThrow().getViewCount();
    }

    // 커밋된 행이 같은 H2 메모리 DB를 쓰는 다른 테스트에 남지 않게 지운다. 조회 기록은 앨범 삭제에 함께 지워진다.
    private void cleanUp(Album album, Member... members) {
        albumRepository.deleteById(album.getId());
        for (Member member : members) {
            memberRepository.deleteById(member.getId());
        }
    }
}
