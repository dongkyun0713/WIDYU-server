package com.widyu.album;

import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.global.entity.BaseTimeEntity;
import com.widyu.global.entity.Status;
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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(indexes = {
    @Index(name = "idx_album_status_created_id", columnList = "status, created_at DESC, album_id DESC")
})
public class Album extends BaseTimeEntity {

    // 앨범 해금 가격. 상세·피드·해금 응답이 모두 이 값을 참조한다.
    public static final long UNLOCK_PRICE = 50L;

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
    @CollectionTable(name = "album_media_url", joinColumns = @JoinColumn(name = "album_id"))
    @Column(name = "media_url")
    @OrderColumn(name = "display_order")
    private List<String> mediaUrls = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "album_thumbnail_url", joinColumns = @JoinColumn(name = "album_id"))
    @Column(name = "thumbnail_url")
    @OrderColumn(name = "display_order")
    private List<String> thumbnailUrls = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "album_duration", joinColumns = @JoinColumn(name = "album_id"))
    @Column(name = "duration")
    @OrderColumn(name = "display_order")
    private List<Integer> durations = new ArrayList<>();

    @OneToMany(mappedBy = "album", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AlbumComment> comments = new ArrayList<>();

    @OneToMany(mappedBy = "album", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AlbumLike> likes = new ArrayList<>();

    @OneToMany(mappedBy = "album", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AlbumView> views = new ArrayList<>();

    @Builder(access = AccessLevel.PRIVATE)
    private Album(Member member, String content, List<String> mediaUrls, List<String> thumbnailUrls, List<Integer> durations, Integer likeCount, Integer commentCount, Integer viewCount, Status status) {
        this.member = member;
        this.content = content;
        this.likeCount = defaultInteger(likeCount);
        this.commentCount = defaultInteger(commentCount);
        this.viewCount = defaultInteger(viewCount);
        this.status = defaultStatus(status);
        this.mediaUrls = defaultList(mediaUrls);
        this.thumbnailUrls = defaultList(thumbnailUrls);
        this.durations = defaultList(durations);
        this.comments = new ArrayList<>();
        this.likes = new ArrayList<>();
        this.views = new ArrayList<>();
    }

    public static Album createAlbumWithMetadata(Member member, String content, List<String> mediaUrls, List<String> thumbnailUrls, List<Integer> durations) {
        return Album.builder()
                .member(member)
                .content(content)
                .mediaUrls(mediaUrls)
                .thumbnailUrls(thumbnailUrls)
                .durations(durations)
                .build();
    }

    public static Album createAlbumForProcessing(Member member, String content, List<String> mediaUrls, List<String> thumbnailUrls, List<Integer> durations) {
        return Album.builder()
                .member(member)
                .content(content)
                .mediaUrls(mediaUrls)
                .thumbnailUrls(thumbnailUrls)
                .durations(durations)
                .status(Status.PROCESSING)
                .build();
    }

    public void completeVideoProcessing(Map<Integer, String> videoUrlsByIndex,
                                        Map<Integer, String> thumbnailUrlsByIndex,
                                        Map<Integer, Integer> durationsByIndex) {
        replaceValuesByIndex(this.mediaUrls, videoUrlsByIndex);
        replaceValuesByIndex(this.thumbnailUrls, thumbnailUrlsByIndex);
        replaceValuesByIndex(this.durations, durationsByIndex);
        this.status = Status.ACTIVE;
    }

    private <T> void replaceValuesByIndex(List<T> values, Map<Integer, T> valuesByIndex) {
        for (Map.Entry<Integer, T> entry : valuesByIndex.entrySet()) {
            while (values.size() <= entry.getKey()) {
                values.add(null);
            }
            values.set(entry.getKey(), entry.getValue());
        }
    }

    // 잠금 대상은 보호자가 올린 앨범뿐이다. 시니어가 올린 앨범은 해금 없이 볼 수 있다.
    public boolean requiresUnlock() {
        return member.getType() == MemberType.GUARDIAN;
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
        if (hasVideo) {
            return MediaType.VIDEO;
        }
        return MediaType.PHOTO;
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

    private static <T> List<T> defaultList(List<T> values) {
        if (values != null) {
            return values;
        }
        return new ArrayList<>();
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
}
