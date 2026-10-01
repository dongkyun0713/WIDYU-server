package com.widyu.fcm.event.album.dto;

public record AlbumCommentedEvent(Long albumId, Long commenterMemberId, Long albumAuthorId,
        Long commentId, Long parentCommentId) {
}
