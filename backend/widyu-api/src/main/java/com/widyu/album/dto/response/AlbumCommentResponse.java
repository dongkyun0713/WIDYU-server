package com.widyu.album.dto.response;

import com.widyu.album.AlbumComment;

import java.time.LocalDateTime;
import java.util.List;

public record AlbumCommentResponse(
        Long commentId,
        Long albumId,
        String content,
        String authorName,
        Long authorId,
        Integer likeCount,
        boolean isReply,
        Long parentCommentId,
        List<AlbumCommentResponse> replies,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    
    public static AlbumCommentResponse from(AlbumComment comment) {
        Long parentCommentId = getParentCommentId(comment);
        return new AlbumCommentResponse(
                comment.getId(),
                comment.getAlbum().getId(),
                comment.getContent(),
                comment.getMember().getName(),
                comment.getMember().getId(),
                comment.getLikeCount(),
                comment.getParentComment() != null,
                parentCommentId,
                comment.getReplies().stream()
                        .map(AlbumCommentResponse::from)
                        .toList(),
                comment.getCreatedAt(),
                comment.getUpdatedAt()
        );
    }
    
    public static AlbumCommentResponse fromWithoutReplies(AlbumComment comment) {
        Long parentCommentId = getParentCommentId(comment);
        return new AlbumCommentResponse(
                comment.getId(),
                comment.getAlbum().getId(),
                comment.getContent(),
                comment.getMember().getName(),
                comment.getMember().getId(),
                comment.getLikeCount(),
                comment.getParentComment() != null,
                parentCommentId,
                List.of(),
                comment.getCreatedAt(),
                comment.getUpdatedAt()
        );
    }

    private static Long getParentCommentId(AlbumComment comment) {
        if (comment.getParentComment() == null) {
            return null;
        }
        return comment.getParentComment().getId();
    }
}
