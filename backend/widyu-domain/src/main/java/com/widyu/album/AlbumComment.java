package com.widyu.album;

import com.widyu.member.Member;
import com.widyu.global.entity.BaseTimeEntity;
import com.widyu.global.entity.Status;
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
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
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

    @Column(name = "depth", nullable = false)
    private Integer depth = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.ACTIVE;

    @OneToMany(mappedBy = "parentComment", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AlbumComment> replies = new ArrayList<>();

    @Builder(access = AccessLevel.PRIVATE)
    private AlbumComment(Album album, Member member, AlbumComment parentComment, String content, 
                        Integer likeCount, Integer depth, Status status) {
        this.album = album;
        this.member = member;
        this.parentComment = parentComment;
        this.content = content;
        this.likeCount = defaultInteger(likeCount);
        this.depth = defaultInteger(depth);
        this.status = defaultStatus(status);
        this.replies = new ArrayList<>();
    }

    public static AlbumComment createComment(Album album, Member member, String content) {
        return AlbumComment.builder()
                .album(album)
                .member(member)
                .content(content)
                .depth(0)
                .build();
    }

    private static Integer defaultInteger(Integer value) {
        if (value != null) {
            return value;
        }
        return 0;
    }

    private static Status defaultStatus(Status value) {
        if (value != null) {
            return value;
        }
        return Status.ACTIVE;
    }

    public static AlbumComment createReply(Album album, Member member, AlbumComment parentComment, String content) {
        return AlbumComment.builder()
                .album(album)
                .member(member)
                .parentComment(parentComment)
                .content(content)
                .depth(parentComment.getDepth() + 1)
                .build();
    }
    
    public void updateContent(String content) {
        this.content = content;
    }
    
    public void delete() {
        this.status = Status.DELETED;
    }
    
    public void deleteWithReplies() {
        this.status = Status.DELETED;
        
        for (AlbumComment reply : this.replies) {
            if (reply.getStatus() == Status.ACTIVE) {
                reply.deleteWithReplies();
            }
        }
    }
}
