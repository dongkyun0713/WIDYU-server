package com.widyu.album.application;

import com.widyu.album.dto.request.AlbumFeedRequest;
import com.widyu.album.dto.request.AlbumUpdateRequest;
import com.widyu.album.dto.request.AlbumUploadRequest;
import com.widyu.album.dto.response.AlbumDetailResponse;
import com.widyu.album.dto.response.AlbumFeedResponse;
import com.widyu.album.dto.response.AlbumUnlockResponse;
import com.widyu.album.dto.response.AlbumUploadAcceptedResponse;
import com.widyu.album.dto.response.AlbumUploadResponse;
import com.widyu.album.dto.response.LikedAlbumsResponse;
import com.widyu.album.dto.response.AlbumMediaResponse;
import com.widyu.global.dto.CursorPage;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 앨범 도메인 파사드 구현체
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlbumFacadeImpl implements AlbumFacade {

    private final AlbumFeedService albumFeedService;
    private final AlbumService albumService;
    private final AlbumLikeService albumLikeService;
    private final AlbumUnlockService albumUnlockService;
    private final AlbumFileService albumFileService;
    private final AlbumMediaPolicy mediaPolicy;
    private final AlbumVideoProcessingService albumVideoProcessingService;
    private final MemberUtil memberUtil;

    @Override
    public AlbumUploadAcceptedResponse uploadAlbum(AlbumUploadRequest request) {
        Member currentMember = memberUtil.getCurrentMember();

        AlbumFileService.AsyncUploadPreparation prep =
                albumFileService.prepareForAsyncUpload(request.mediaFiles(), currentMember.getId());

        Long albumId = albumService.saveAlbum(
                currentMember, request.content(),
                prep.mediaUrls(), prep.thumbnailUrls(), prep.durations(),
                prep.hasVideos());

        if (prep.hasVideos()) {
            albumVideoProcessingService.processVideosAsync(albumId, currentMember.getId(), prep.videoEntries());
        }

        return AlbumUploadAcceptedResponse.from(albumId);
    }

    @Override
    public CursorPage<AlbumFeedResponse> getAlbumFeed(String cursor, String date) {
        AlbumFeedRequest request = AlbumFeedRequest.from(cursor, date);
        return albumFeedService.getAlbumFeed(request);
    }
    
    @Override
    public CursorPage<AlbumMediaResponse> getMediaFeed(String cursor) {
        AlbumFeedRequest parsed = AlbumFeedRequest.from(cursor, null);
        return albumFeedService.getMediaFeed(parsed.lastCreatedAt(), parsed.lastAlbumId());
    }

    @Override
    public AlbumDetailResponse getAlbumDetail(Long albumId) {
        return albumService.getAlbumDetail(albumId);
    }

    @Override
    public AlbumUploadResponse updateAlbum(Long albumId, AlbumUpdateRequest request) {
        return albumService.updateAlbum(albumId, request);
    }
    
    @Override
    public void deleteAlbum(Long albumId) {
        albumService.deleteAlbum(albumId);
    }
    
    @Override
    public void likeAlbum(Long albumId) {
        albumLikeService.likeAlbum(albumId);
    }
    
    @Override
    public void unlikeAlbum(Long albumId) {
        albumLikeService.unlikeAlbum(albumId);
    }
    
    @Override
    public LikedAlbumsResponse getLikedAlbumIds() {
        return albumLikeService.getLikedAlbumIds();
    }
    
    @Override
    public AlbumUnlockResponse unlockAlbum(Long albumId) {
        return albumUnlockService.unlockAlbum(albumId);
    }
}
