package com.widyu.domain.album.entity;

import com.widyu.domain.member.entity.Member;
import com.widyu.global.domain.BaseTimeEntity;
import com.widyu.global.domain.Status;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "album_comments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AlbumComment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "comment_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "album_id", nullable = false)
    private Album album;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_comment_id")
    private AlbumComment parentComment;

    @Column(name = "content", nullable = false, length = 500)
    private String content;

    @Column(name = "like_count", nullable = false)
    private Integer likeCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.ACTIVE;

    @OneToMany(mappedBy = "parentComment", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AlbumComment> replies = new ArrayList<>();

    @OneToMany(mappedBy = "comment", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AlbumCommentLike> likes = new ArrayList<>();

    @Builder(access = AccessLevel.PRIVATE)
    private AlbumComment(Album album, Member member, AlbumComment parentComment, String content, 
                        Integer likeCount, Status status) {
        this.album = album;
        this.member = member;
        this.parentComment = parentComment;
        this.content = content;
        this.likeCount = likeCount != null ? likeCount : 0;
        this.status = status != null ? status : Status.ACTIVE;
        this.replies = new ArrayList<>();
        this.likes = new ArrayList<>();
    }

    public static AlbumComment createComment(Album album, Member member, String content) {
        return AlbumComment.builder()
                .album(album)
                .member(member)
                .content(content)
                .build();
    }

    public static AlbumComment createReply(Album album, Member member, AlbumComment parentComment, String content) {
        return AlbumComment.builder()
                .album(album)
                .member(member)
                .parentComment(parentComment)
                .content(content)
                .build();
    }
}
