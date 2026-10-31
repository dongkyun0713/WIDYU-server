package com.widyu.album.dto.response;

import com.widyu.album.Album;
import com.widyu.member.Member;
import com.widyu.album.MediaType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

public record AlbumFeedResponse(
        Long albumId,
        String authorName,
        String authorProfileImage,
        String content,
        List<String> mediaUrls,
        List<String> thumbnailUrls,
        MediaType primaryMediaType,
        Integer mediaCount,
        Integer photoCount,
        Integer videoCount,
        Integer likeCount,
        Integer commentCount,
        Integer viewCount,
        List<ViewerInfo> viewers,
        LocalDateTime createdAt,
        Boolean canEdit,
        Boolean isUnlocked,
        Long price,
        String videoDuration // 동영상인 경우만
) {
    public record ViewerInfo(
            String name,
            String profileImage
    ) {
        public static ViewerInfo from(Member member) {
            return new ViewerInfo(member.getName(), member.getProfileImage());
        }
    }

    public static AlbumFeedResponse from(Album album, Boolean canEdit, Boolean isUnlocked, List<ViewerInfo> viewers) {
        String primaryVideoDuration = album.getDurations().stream()
                .filter(Objects::nonNull)
                .findFirst()
                .map(d -> String.format("%d:%02d", d / 60, d % 60))
                .orElse(null);

        // 잠금 전에는 원본 URL을 싣지 않고 썸네일만 준다(#813)
        List<String> mediaUrls = List.of();
        if (Boolean.TRUE.equals(isUnlocked)) {
            mediaUrls = album.getMediaUrls();
        }

        return new AlbumFeedResponse(
                album.getId(),
                album.getMember().getName(),
                album.getMember().getProfileImage(),
                album.getContent(),
                mediaUrls,
                album.getThumbnailUrls(),
                album.getPrimaryMediaType(),
                album.getMediaCount(),
                album.getPhotoCount(),
                album.getVideoCount(),
                album.getLikeCount(),
                album.getCommentCount(),
                album.getViewCount(),
                viewers,
                album.getCreatedAt(),
                canEdit,
                isUnlocked,
                Album.UNLOCK_PRICE,
                primaryVideoDuration
        );
    }
}