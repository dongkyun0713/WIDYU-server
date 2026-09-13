package com.widyu.album.dto.response;

import com.widyu.album.AlbumUnlock;

import java.time.LocalDateTime;

public record AlbumUnlockResponse(
        Long unlockId,
        Long albumId,
        String albumTitle,
        LocalDateTime unlockedAt,
        Long remainingPoints,
        String message
) {
    
    public static AlbumUnlockResponse from(AlbumUnlock albumUnlock, Long remainingPoints) {
        String albumTitle = albumUnlock.getAlbum().getContent();
        if (albumTitle.length() > 50) {
            albumTitle = albumTitle.substring(0, 50) + "...";
        }
        return new AlbumUnlockResponse(
                albumUnlock.getId(),
                albumUnlock.getAlbum().getId(),
                albumTitle,
                albumUnlock.getUnlockedAt(),
                remainingPoints,
                "앨범이 해금되었습니다."
        );
    }
}
