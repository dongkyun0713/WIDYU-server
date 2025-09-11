package com.widyu.domain.album.entity;

import com.widyu.domain.member.entity.Member;
import com.widyu.global.domain.BaseTimeEntity;
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
    name = "album_comment_likes",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_album_comment_likes_comment_member",
        columnNames = {"comment_id", "member_id"}
    )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AlbumCommentLike extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "like_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comment_id", nullable = false)
    private AlbumComment comment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Builder(access = AccessLevel.PRIVATE)
    private AlbumCommentLike(AlbumComment comment, Member member) {
        this.comment = comment;
        this.member = member;
    }

    public static AlbumCommentLike createLike(AlbumComment comment, Member member) {
        return AlbumCommentLike.builder()
                .comment(comment)
                .member(member)
                .build();
    }
}