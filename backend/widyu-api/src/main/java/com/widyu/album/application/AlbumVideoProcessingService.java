package com.widyu.album.application;

import com.widyu.album.Album;
import com.widyu.album.repository.AlbumRepository;
import com.widyu.fcm.event.album.dto.AlbumCreatedEvent;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.config.AsyncConfig;
import com.widyu.global.infrastructure.s3.S3DirectUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlbumVideoProcessingService {

    private final AlbumRepository albumRepository;
    private final AlbumFileService albumFileService;
    private final S3DirectUploadService s3DirectUploadService;
    private final ApplicationEventPublisher eventPublisher;

    public record VideoEntry(int index, File tempFile, String originalFileName, String contentType) {}

    public record StagedVideoEntry(int index, String objectKey, String originalFileName, String contentType) {}

    @Async(AsyncConfig.ALBUM_VIDEO_EXECUTOR)
    @Transactional
    public void processVideosAsync(Long albumId, Long memberId, List<VideoEntry> videoEntries) {
        processVideos(albumId, memberId, videoEntries);
    }

    @Async(AsyncConfig.ALBUM_VIDEO_EXECUTOR)
    @Transactional
    public void processStagedVideosAsync(Long albumId, Long memberId, List<StagedVideoEntry> stagedEntries) {
        List<VideoEntry> videoEntries = new ArrayList<>();

        try {
            for (StagedVideoEntry entry : stagedEntries) {
                File tempFile = s3DirectUploadService.downloadToTempFile(entry.objectKey());
                videoEntries.add(new VideoEntry(entry.index(), tempFile, entry.originalFileName(), entry.contentType()));
            }
        } catch (Exception e) {
            log.error("스테이징 영상 다운로드 실패: albumId={}, error={}", albumId, e.getMessage(), e);
            deleteTempFiles(videoEntries);
            deleteStagedObjects(stagedEntries);
            albumRepository.findById(albumId).ifPresent(Album::delete);
            return;
        }

        try {
            processVideos(albumId, memberId, videoEntries);
        } finally {
            deleteStagedObjects(stagedEntries);
        }
    }

    private void processVideos(Long albumId, Long memberId, List<VideoEntry> videoEntries) {
        Map<Integer, String> videoUrls = new HashMap<>();
        Map<Integer, String> thumbnailUrls = new HashMap<>();
        Map<Integer, Integer> durations = new HashMap<>();
        List<String> uploadedUrls = new ArrayList<>();

        try {
            for (VideoEntry entry : videoEntries) {
                MultipartFile videoFile = wrapFile(entry.tempFile(), entry.originalFileName(), entry.contentType());
                AlbumFileService.VideoUploadResult result = albumFileService.uploadAlbumVideoWithThumbnail(videoFile, memberId);
                videoUrls.put(entry.index(), result.videoUrl());
                thumbnailUrls.put(entry.index(), result.thumbnailUrl());
                durations.put(entry.index(), result.duration());
                uploadedUrls.add(result.videoUrl());
                uploadedUrls.add(result.thumbnailUrl());
            }

            Album album = albumRepository.findById(albumId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ALBUM_NOT_FOUND));
            album.completeVideoProcessing(videoUrls, thumbnailUrls, durations);

            eventPublisher.publishEvent(new AlbumCreatedEvent(albumId, memberId));
            log.info("비디오 비동기 처리 완료: albumId={}", albumId);

        } catch (Exception e) {
            log.error("비디오 비동기 처리 실패: albumId={}, error={}", albumId, e.getMessage(), e);
            albumFileService.cleanupUploadedFiles(uploadedUrls);
            albumRepository.findById(albumId).ifPresent(Album::delete);
        } finally {
            deleteTempFiles(videoEntries);
        }
    }

    private void deleteTempFiles(List<VideoEntry> videoEntries) {
        for (VideoEntry entry : videoEntries) {
            try {
                Files.deleteIfExists(entry.tempFile().toPath());
            } catch (IOException ignored) {
                log.warn("비디오 임시 파일 삭제 실패: path={}", entry.tempFile().getAbsolutePath());
            }
        }
    }

    private void deleteStagedObjects(List<StagedVideoEntry> stagedEntries) {
        for (StagedVideoEntry entry : stagedEntries) {
            try {
                s3DirectUploadService.deleteObject(entry.objectKey());
            } catch (Exception e) {
                log.warn("스테이징 원본 삭제 실패: key={}, error={}", entry.objectKey(), e.getMessage());
            }
        }
    }

    private MultipartFile wrapFile(File file, String originalName, String contentType) {
        final Path path = file.toPath();
        return new MultipartFile() {
            @NotNull
            @Override public String getName() { return "file"; }
            @Override public String getOriginalFilename() { return originalName; }
            @Override public String getContentType() { return contentType; }
            @Override public boolean isEmpty() { return file.length() == 0; }
            @Override public long getSize() { return file.length(); }
            @NotNull
            @Override public byte[] getBytes() throws IOException { return Files.readAllBytes(path); }
            @NotNull
            @Override public InputStream getInputStream() throws IOException { return new FileInputStream(file); }
            @Override public void transferTo(@NotNull File dest) throws IOException { Files.copy(path, dest.toPath()); }
        };
    }
}
