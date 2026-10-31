package com.widyu.album.dto.response;

import com.widyu.album.Album;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public record AlbumMediaResponse(
        Long id,
        Long postId,
        String type,
        Integer duration,
        String thumbnailUrl,
        LocalDateTime createdAt,
        @Schema(description = "소속 앨범 전체의 잠금 해제 가격(포인트)", example = "50")
        Long price,
        @Schema(description = "소속 앨범의 잠금 해제 여부. false면 thumbnailUrl에 저장된 썸네일만 담습니다.", example = "true")
        boolean isUnlocked
) {

    public static List<AlbumMediaResponse> fromAlbum(Album album, boolean isUnlocked) {
        List<AlbumMediaResponse> albumMediaResponses = new ArrayList<>();
        List<String> mediaUrls = album.getMediaUrls();
        List<String> thumbnailUrls = album.getThumbnailUrls();
        List<Integer> durations = album.getDurations();
        
        for (int i = 0; i < mediaUrls.size(); i++) {
            String mediaUrl = mediaUrls.get(i);
            String thumbnailUrl = null;
            if (i < thumbnailUrls.size()) {
                thumbnailUrl = thumbnailUrls.get(i);
            }
            Integer duration = null;
            if (i < durations.size()) {
                duration = durations.get(i);
            }
            
            // 이미지는 duration이 없다. 해금 후에만 썸네일 자리에 원본 URL을 쓰고, 잠금 전에는 저장된 썸네일만 준다(#813)
            if (isImageUrl(mediaUrl)) {
                duration = null;
                if (isUnlocked) {
                    thumbnailUrl = mediaUrl;
                }
            }
            
            String type = "video";
            if (isImageUrl(mediaUrl)) {
                type = "image";
            }
            albumMediaResponses.add(new AlbumMediaResponse(
                    generateMediaId(album.getId(), i), // 앨범ID + 인덱스로 고유 ID 생성
                    album.getId(),
                    type,
                    duration, // 동영상은 실제 duration, 이미지는 null
                    thumbnailUrl,
                    album.getCreatedAt(),
                    Album.UNLOCK_PRICE,
                    isUnlocked
            ));
        }
        
        return albumMediaResponses;
    }
    
    private static Long generateMediaId(Long albumId, int index) {
        return Long.valueOf(albumId + "00" + index); // 예: 앨범12 + 인덱스0 = 12000
    }
    
    private static boolean isImageUrl(String url) {
        if (url == null) return false;
        String extension = getFileExtension(url).toLowerCase();
        return extension.matches("jpg|jpeg|png|gif|webp|bmp|svg");
    }
    
    private static String getFileExtension(String url) {
        if (url == null || !url.contains(".")) {
            return "";
        }
        return url.substring(url.lastIndexOf(".") + 1);
    }
}
