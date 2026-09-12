package com.widyu.admin.dto.response;

import com.widyu.album.Album;
import com.widyu.global.entity.Status;
import java.time.LocalDateTime;

public record AdminAlbumResponse(
        Long id,
        Long memberId,
        String memberName,
        String thumbnail,
        String contentPreview,
        int likeCount,
        int commentCount,
        int viewCount,
        Status status,
        LocalDateTime createdAt
) {
    public static AdminAlbumResponse from(Album album) {
        String thumbnail = null;
        if (!album.getThumbnailUrls().isEmpty()) {
            thumbnail = album.getThumbnailUrls().get(0);
        }
        String preview = album.getContent();
        if (preview != null && preview.length() > 50) {
            preview = preview.substring(0, 50) + "...";
        }
        return new AdminAlbumResponse(
                album.getId(),
                album.getMember().getId(),
                album.getMember().getName(),
                thumbnail,
                preview,
                album.getLikeCount(),
                album.getCommentCount(),
                album.getViewCount(),
                album.getStatus(),
                album.getCreatedAt()
        );
    }
}
