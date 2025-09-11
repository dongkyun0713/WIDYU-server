package com.widyu.domain.album.entity;

import com.widyu.domain.member.entity.Member;
import com.widyu.global.domain.BaseTimeEntity;
import com.widyu.global.domain.Status;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "albums")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Album extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "album_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "content", length = 2200)
    private String content;

    @Column(name = "like_count", nullable = false)
    private Integer likeCount = 0;

    @Column(name = "comment_count", nullable = false)
    private Integer commentCount = 0;

    @Column(name = "view_count", nullable = false)
    private Integer viewCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.ACTIVE;

    @ElementCollection
    @CollectionTable(name = "album_media_urls", joinColumns = @JoinColumn(name = "album_id"))
    @Column(name = "media_url")
    @OrderColumn(name = "display_order")
    private List<String> mediaUrls = new ArrayList<>();

    @OneToMany(mappedBy = "album", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AlbumComment> comments = new ArrayList<>();

    @OneToMany(mappedBy = "album", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AlbumLike> likes = new ArrayList<>();

    @OneToMany(mappedBy = "album", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AlbumView> views = new ArrayList<>();

    @Builder(access = AccessLevel.PRIVATE)
    private Album(Member member, String content, List<String> mediaUrls, Integer likeCount, Integer commentCount, Integer viewCount, Status status) {
        this.member = member;
        this.content = content;
        this.likeCount = likeCount != null ? likeCount : 0;
        this.commentCount = commentCount != null ? commentCount : 0;
        this.viewCount = viewCount != null ? viewCount : 0;
        this.status = status != null ? status : Status.ACTIVE;
        this.mediaUrls = mediaUrls != null ? mediaUrls : new ArrayList<>();
        this.comments = new ArrayList<>();
        this.likes = new ArrayList<>();
        this.views = new ArrayList<>();
    }

    public static Album createAlbum(Member member, String content, List<String> mediaUrls) {
        return Album.builder()
                .member(member)
                .content(content)
                .mediaUrls(mediaUrls)
                .build();
    }

    public static Album createAlbumWithCounts(Member member, String content, List<String> mediaUrls, 
                                            Integer likeCount, Integer commentCount, Integer viewCount) {
        return Album.builder()
                .member(member)
                .content(content)
                .mediaUrls(mediaUrls)
                .likeCount(likeCount)
                .commentCount(commentCount)
                .viewCount(viewCount)
                .build();
    }

    public void updateContent(String content) {
        this.content = content;
    }

    public void incrementLikeCount() {
        this.likeCount++;
    }

    public void decrementLikeCount() {
        if (this.likeCount > 0) {
            this.likeCount--;
        }
    }

    public void incrementCommentCount() {
        this.commentCount++;
    }

    public void decrementCommentCount() {
        if (this.commentCount > 0) {
            this.commentCount--;
        }
    }

    public void incrementViewCount() {
        this.viewCount++;
    }

    public void delete() {
        this.status = Status.DELETED;
    }

    public boolean isDeleted() {
        return this.status == Status.DELETED;
    }

    public boolean hasMedia() {
        return !mediaUrls.isEmpty();
    }

    public String getFirstMediaUrl() {
        return mediaUrls.isEmpty() ? null : mediaUrls.get(0);
    }

    public boolean hasMultipleMedia() {
        return mediaUrls.size() > 1;
    }

    public void addMediaUrl(String mediaUrl) {
        this.mediaUrls.add(mediaUrl);
    }

    public void updateMediaUrls(List<String> mediaUrls) {
        this.mediaUrls = mediaUrls != null ? mediaUrls : new ArrayList<>();
    }

    public List<String> getPhotoUrls() {
        return mediaUrls.stream()
                .filter(this::isPhotoUrl)
                .toList();
    }

    public List<String> getVideoUrls() {
        return mediaUrls.stream()
                .filter(this::isVideoUrl)
                .toList();
    }

    public int getPhotoCount() {
        return (int) mediaUrls.stream().filter(this::isPhotoUrl).count();
    }

    public int getVideoCount() {
        return (int) mediaUrls.stream().filter(this::isVideoUrl).count();
    }

    public int getMediaCount() {
        return mediaUrls.size();
    }

    public MediaType getPrimaryMediaType() {
        if (mediaUrls.isEmpty()) {
            return null;
        }

        // 동영상이 하나라도 있으면 VIDEO, 아니면 PHOTO
        boolean hasVideo = mediaUrls.stream().anyMatch(this::isVideoUrl);
        return hasVideo ? MediaType.VIDEO : MediaType.PHOTO;
    }
    
    public boolean hasMixedMedia() {
        boolean hasPhoto = mediaUrls.stream().anyMatch(this::isPhotoUrl);
        boolean hasVideo = mediaUrls.stream().anyMatch(this::isVideoUrl);
        return hasPhoto && hasVideo;
    }

    private boolean isPhotoUrl(String url) {
        if (url == null) return false;
        String extension = getFileExtension(url).toLowerCase();
        return extension.matches("jpg|jpeg|png|gif|webp|bmp|svg");
    }

    private boolean isVideoUrl(String url) {
        if (url == null) return false;
        String extension = getFileExtension(url).toLowerCase();
        return extension.matches("mp4|mov|avi|mkv|webm|flv|wmv");
    }

    private String getFileExtension(String url) {
        if (url == null || !url.contains(".")) {
            return "";
        }
        return url.substring(url.lastIndexOf(".") + 1);
    }
}
