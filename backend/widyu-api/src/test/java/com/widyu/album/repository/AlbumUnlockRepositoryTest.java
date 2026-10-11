package com.widyu.album.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.album.Album;
import com.widyu.album.AlbumUnlock;
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
class AlbumUnlockRepositoryTest {

    @Autowired private TestEntityManager entityManager;
    @Autowired private AlbumUnlockRepository albumUnlockRepository;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @Test
    @DisplayName("잠금 해제 뒤 남은 수를 세면 그 보호자의 활성 미해제 게시물만 포함한다")
    void 잠금_해제_뒤_남은_수를_세면_그_보호자의_활성_미해제_게시물만_포함한다() {
        // given
        Member writer = entityManager.persist(Member.createMember(MemberType.GUARDIAN, "작성자", "01011112222"));
        Member otherWriter = entityManager.persist(Member.createMember(MemberType.GUARDIAN, "다른 작성자", "01022223333"));
        Member senior = entityManager.persist(Member.createMember(MemberType.SENIOR, "시니어", "01033334444"));
        Album justUnlocked = entityManager.persist(activeAlbum(writer));
        entityManager.persist(activeAlbum(writer));
        Album alreadyUnlocked = entityManager.persist(activeAlbum(writer));
        entityManager.persist(activeAlbum(otherWriter));
        entityManager.persist(Album.createAlbumForProcessing(writer, "처리 중", List.of(), List.of(), List.of()));
        entityManager.persist(AlbumUnlock.createUnlock(justUnlocked, senior));
        entityManager.persist(AlbumUnlock.createUnlock(alreadyUnlocked, senior));
        entityManager.flush();

        // when
        long count = albumUnlockRepository.countRemainingLockedByWriterAndSenior(
                writer.getId(), senior.getId(), justUnlocked.getId());

        // then
        assertThat(count).isEqualTo(1);
    }

    private Album activeAlbum(Member writer) {
        return Album.createAlbumWithMetadata(writer, "게시물", List.of(), List.of(), List.of());
    }
}
