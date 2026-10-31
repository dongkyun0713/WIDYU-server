package com.widyu.album.dto.response;

import com.widyu.album.Album;
import com.widyu.album.AlbumComment;
import com.widyu.member.Member;

import java.time.LocalDateTime;
import java.util.List;

public record AlbumDetailResponse(
        Long postId,
        String content,
        List<String> mediaUrls,
        List<String> thumbnailUrls,
        Integer likeCount,
        Integer commentCount,
        Integer viewCount,
        LocalDateTime createdAt,
        AuthorInfo author,
        List<ViewerInfo> viewers,
        Long price,
        List<CommentInfo> comments,
        boolean canEdit,
        boolean isUnlocked
) {

    public record AuthorInfo(
            Long memberId,
            String name,
            String profileImage
    ) {
        public static AuthorInfo from(Member member) {
            return new AuthorInfo(
                    member.getId(),
                    member.getName(),
                    member.getProfileImage()
            );
        }
    }

    public record ViewerInfo(
            Long memberId,
            String name,
            String profileImage
    ) {
        public static ViewerInfo from(Member member) {
            return new ViewerInfo(
                    member.getId(),
                    member.getName(),
                    member.getProfileImage()
            );
        }
    }

    public record CommentInfo(
            Long commentId,
            String content,
            LocalDateTime createdAt,
            AuthorInfo author,
            boolean canEdit,
            List<ReplyInfo> replies
    ) {
        public static CommentInfo from(AlbumComment comment, Long currentUserId) {
            return new CommentInfo(
                    comment.getId(),
                    comment.getContent(),
                    comment.getCreatedAt(),
                    AuthorInfo.from(comment.getMember()),
                    comment.getMember().getId().equals(currentUserId),
                    comment.getReplies().stream()
                            .filter(reply -> reply.getStatus().name().equals("ACTIVE"))
                            .map(reply -> ReplyInfo.from(reply, currentUserId))
                            .toList()
            );
        }
    }

    public record ReplyInfo(
            Long commentId,
            String content,
            LocalDateTime createdAt,
            AuthorInfo author,
            boolean canEdit
    ) {
        public static ReplyInfo from(AlbumComment reply, Long currentUserId) {
            return new ReplyInfo(
                    reply.getId(),
                    reply.getContent(),
                    reply.getCreatedAt(),
                    AuthorInfo.from(reply.getMember()),
                    reply.getMember().getId().equals(currentUserId)
            );
        }
    }

    public static AlbumDetailResponse from(Album album, Long currentUserId, List<Member> viewers,
                                           List<AlbumComment> comments, boolean isUnlocked) {
        // 잠금 전에는 원본 URL을 싣지 않고 썸네일만 준다(#813)
        List<String> mediaUrls = List.of();
        if (isUnlocked) {
            mediaUrls = album.getMediaUrls();
        }
        return new AlbumDetailResponse(
                album.getId(),
                album.getContent(),
                mediaUrls,
                album.getThumbnailUrls(),
                album.getLikeCount(),
                album.getCommentCount(),
                album.getViewCount(),
                album.getCreatedAt(),
                AuthorInfo.from(album.getMember()),
                viewers.stream().map(ViewerInfo::from).toList(),
                Album.UNLOCK_PRICE,
                comments.stream()
                        .map(comment -> CommentInfo.from(comment, currentUserId))
                        .toList(),
                album.getMember().getId().equals(currentUserId),
                isUnlocked
        );
    }
}
