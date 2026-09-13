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

@Entity
@Table(
    uniqueConstraints = @UniqueConstraint(
        name = "uk_album_views_album_member",
        columnNames = {"album_id", "member_id"}
    )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AlbumView extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "view_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "album_id", nullable = false)
    private Album album;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "view_count", nullable = false)
    private Integer viewCount = 1;

    @Builder(access = AccessLevel.PRIVATE)
    private AlbumView(Album album, Member member, Integer viewCount) {
        this.album = album;
        this.member = member;
        if (viewCount != null) {
            this.viewCount = viewCount;
        } else {
            this.viewCount = 1;
        }
    }

    public static AlbumView createView(Album album, Member member) {
        return AlbumView.builder()
                .album(album)
                .member(member)
                .viewCount(1)
                .build();
    }

    public void incrementViewCount() {
        this.viewCount++;
    }
}
