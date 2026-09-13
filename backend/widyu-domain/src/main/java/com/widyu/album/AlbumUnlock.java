package com.widyu.album;

import com.widyu.member.Member;
import com.widyu.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
    uniqueConstraints = @UniqueConstraint(
        name = "uk_album_unlocks_album_member",
        columnNames = {"album_id", "member_id"}
    )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AlbumUnlock extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "unlock_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "album_id", nullable = false)
    private Album album;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;


    @Column(name = "unlocked_at", nullable = false)
    private LocalDateTime unlockedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private AlbumUnlock(Album album, Member member, LocalDateTime unlockedAt) {
        this.album = album;
        this.member = member;
        if (unlockedAt != null) {
            this.unlockedAt = unlockedAt;
        } else {
            this.unlockedAt = LocalDateTime.now();
        }
    }

    public static AlbumUnlock createUnlock(Album album, Member member) {
        return AlbumUnlock.builder()
                .album(album)
                .member(member)
                .unlockedAt(LocalDateTime.now())
                .build();
    }
}
