package com.widyu.album.dto;

import com.widyu.album.Album;
import com.widyu.member.Member;

import java.time.LocalDateTime;
import java.util.List;

public record FamilyAlbumResponse(
        Long albumId,
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
        boolean isUnlocked
) {
    public static FamilyAlbumResponse from(Album album, List<Member> viewers, boolean isUnlocked) {
        Member member = album.getMember();

        // 잠금 전에는 원본 URL을 싣지 않고 썸네일만 준다(#813)
        List<String> urls = List.of();
        if (isUnlocked && album.getMediaUrls() != null) {
            urls = List.copyOf(album.getMediaUrls());
        }

        List<ViewerInfo> viewerInfos = viewers.stream()
                .map(ViewerInfo::from)
                .toList();

        return new FamilyAlbumResponse(
                album.getId(),
                album.getContent(),
                urls,
                album.getThumbnailUrls(),
                album.getLikeCount(),
                album.getCommentCount(),
                album.getViewCount(),
                album.getCreatedAt(),
                AuthorInfo.from(member),
                viewerInfos,
                Album.UNLOCK_PRICE,
                isUnlocked
        );
    }

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
}
