package com.widyu.album.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.album.Album;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
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
@Import(JpaAuditingConfig.class)
@DisplayName("해금 없이 볼 수 있는 시니어 앨범 ID 조회 테스트")
class AlbumUnlockFreeAlbumIdsQueryTest {

    @Autowired private AlbumUnlockRepository albumUnlockRepository;
    @Autowired private TestEntityManager entityManager;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @Test
    @DisplayName("동영상 처리 중인 시니어 앨범은 포함하고 삭제된 앨범과 보호자 앨범은 제외한다")
    void 처리_중인_시니어_앨범을_포함하고_삭제된_앨범은_제외한다() {
        // given
        Member senior = entityManager.persist(Member.createMember(MemberType.SENIOR, "시니어", "01000000001"));
        Member guardian = entityManager.persist(Member.createMember(MemberType.GUARDIAN, "보호자", "01000000002"));
        Album active = persist(Album.createAlbumWithMetadata(senior, "사진", List.of("a.jpg"), List.of("a.jpg"), List.of()));
        Album processing = persist(Album.createAlbumForProcessing(senior, "영상", List.of(), List.of(), List.of()));
        Album deleted = Album.createAlbumWithMetadata(senior, "삭제", List.of("d.jpg"), List.of("d.jpg"), List.of());
        deleted.delete();
        persist(deleted);
        persist(Album.createAlbumWithMetadata(guardian, "보호자", List.of("g.jpg"), List.of("g.jpg"), List.of()));
        entityManager.flush();
        entityManager.clear();

        // when
        List<Long> albumIds = albumUnlockRepository.findActiveOrProcessingAlbumIdsByMemberIdsAndMemberType(
                List.of(senior.getId(), guardian.getId()), MemberType.SENIOR);

        // then
        assertThat(albumIds).containsExactlyInAnyOrder(active.getId(), processing.getId());
    }

    private Album persist(Album album) {
        return entityManager.persist(album);
    }
}
