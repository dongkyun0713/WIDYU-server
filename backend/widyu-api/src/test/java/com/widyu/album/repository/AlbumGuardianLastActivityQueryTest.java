package com.widyu.album.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.album.Album;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.member.Family;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import java.time.LocalDateTime;
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
class AlbumGuardianLastActivityQueryTest {

    @Autowired private AlbumRepository albumRepository;
    @Autowired private TestEntityManager entityManager;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @Test
    @DisplayName("보호자별 마지막 ACTIVE 업로드 시각을 집계하고 업로드가 없으면 가입 시각으로 범위를 판정한다")
    void 보호자별_마지막_업로드_시각으로_범위를_판정한다() {
        // given
        Family family = entityManager.persistAndFlush(Family.createFamily("INA123"));
        Member uploader = guardian(family, "업로더", "01011110001");
        album(uploader, LocalDateTime.of(2026, 10, 10, 9, 0), "ACTIVE");
        album(uploader, LocalDateTime.of(2026, 10, 19, 10, 0), "ACTIVE");
        album(uploader, LocalDateTime.of(2026, 10, 21, 8, 0), "DELETED");
        Member neverUploaded = guardian(family, "미업로드", "01011110002");
        setCreatedAt("member", "id", neverUploaded.getId(), LocalDateTime.of(2026, 10, 17, 12, 0));
        Member recent = guardian(family, "최근", "01011110003");
        album(recent, LocalDateTime.of(2026, 10, 21, 8, 0), "ACTIVE");
        entityManager.clear();

        // when
        List<GuardianLastActivityRow> rows = albumRepository.findGuardianLastActivityBetween(
                LocalDateTime.of(2026, 10, 15, 0, 0), LocalDateTime.of(2026, 10, 20, 0, 0));

        // then
        assertThat(rows).extracting(GuardianLastActivityRow::guardianId, GuardianLastActivityRow::lastActivityAt)
                .containsExactlyInAnyOrder(
                        tuple(uploader.getId(), LocalDateTime.of(2026, 10, 19, 10, 0)),
                        tuple(neverUploaded.getId(), LocalDateTime.of(2026, 10, 17, 12, 0)));
    }

    private Member guardian(Family family, String name, String phone) {
        Member member = entityManager.persistAndFlush(Member.createMember(MemberType.GUARDIAN, name, phone));
        entityManager.persistAndFlush(FamilyMembership.createMembership(family, member));
        return member;
    }

    private void album(Member writer, LocalDateTime createdAt, String status) {
        Album album = entityManager.persistAndFlush(
                Album.createAlbumWithMetadata(writer, "내용", List.of("m.jpg"), List.of("t.jpg"), List.of(0)));
        setCreatedAt("album", "album_id", album.getId(), createdAt);
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE album SET status = ?1 WHERE album_id = ?2")
                .setParameter(1, status).setParameter(2, album.getId()).executeUpdate();
    }

    private void setCreatedAt(String table, String idColumn, Long id, LocalDateTime createdAt) {
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE " + table + " SET created_at = ?1 WHERE " + idColumn + " = ?2")
                .setParameter(1, createdAt).setParameter(2, id).executeUpdate();
    }
}
