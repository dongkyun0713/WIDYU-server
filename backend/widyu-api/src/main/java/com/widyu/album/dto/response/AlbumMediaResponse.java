package com.widyu.album.dto.response;

import com.widyu.album.Album;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public record AlbumMediaResponse(
        Long id,
        Long postId,
        String type,
        Integer duration,
        String thumbnailUrl,
        LocalDateTime createdAt
) {

    public static List<AlbumMediaResponse> fromAlbum(Album album) {
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
            
            // 이미지인 경우 썸네일 URL을 미디어 URL로 사용, duration은 null
            if (isImageUrl(mediaUrl)) {
                thumbnailUrl = mediaUrl;
                duration = null;
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
                    album.getCreatedAt()
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
