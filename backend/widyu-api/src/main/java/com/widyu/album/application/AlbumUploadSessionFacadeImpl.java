package com.widyu.album.application;

import com.widyu.album.AlbumUploadSession;
import com.widyu.album.AlbumUploadSessionFile;
import com.widyu.album.dto.request.AlbumUploadCompleteRequest;
import com.widyu.album.dto.request.AlbumUploadSessionCreateRequest;
import com.widyu.album.dto.response.AlbumUploadAcceptedResponse;
import com.widyu.album.dto.response.AlbumUploadSessionResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.infrastructure.s3.S3DirectUploadService;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * Presigned URL 기반 앨범 직접 업로드 파사드
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlbumUploadSessionFacadeImpl implements AlbumUploadSessionFacade {

    private static final String STAGING_PREFIX = "albums/staging";
    private static final String ALBUM_PHOTO_PREFIX = "albums/photos";
    private static final long PART_SIZE_BYTES = 10L * 1024 * 1024;
    private static final Duration PRESIGN_EXPIRY = Duration.ofHours(1);

    private final AlbumUploadSessionService albumUploadSessionService;
    private final AlbumService albumService;
    private final AlbumVideoProcessingService albumVideoProcessingService;
    private final AlbumMediaPolicy mediaPolicy;
    private final S3DirectUploadService s3DirectUploadService;
    private final S3Service s3Service;
    private final MemberUtil memberUtil;

    @Override
    public AlbumUploadSessionResponse createUploadSession(AlbumUploadSessionCreateRequest request) {
        Member currentMember = memberUtil.getCurrentMember();

        List<AlbumMediaPolicy.MediaMetadata> metadata = request.files().stream()
                .map(file -> new AlbumMediaPolicy.MediaMetadata(file.contentType(), file.fileSize()))
                .toList();
        mediaPolicy.validateMetadata(metadata);

        String sessionId = UUID.randomUUID().toString();
        List<AlbumUploadSessionFile> sessionFiles = new ArrayList<>();
        List<AlbumUploadSessionResponse.FileUploadTarget> targets = new ArrayList<>();

        for (int index = 0; index < request.files().size(); index++) {
            AlbumUploadSessionCreateRequest.FileMetadata file = request.files().get(index);
            String objectKey = generateStagingKey(currentMember.getId(), sessionId, index, file.fileName());

            if (file.contentType().startsWith("image/")) {
                String uploadUrl = s3DirectUploadService.presignPut(
                        objectKey, file.contentType(), file.fileSize(), PRESIGN_EXPIRY);
                sessionFiles.add(AlbumUploadSessionFile.photo(
                        index, file.fileName(), file.contentType(), file.fileSize(), objectKey));
                targets.add(AlbumUploadSessionResponse.FileUploadTarget.photo(index, objectKey, uploadUrl));
                continue;
            }

            String uploadId = s3DirectUploadService.createMultipartUpload(objectKey, file.contentType());
            int partCount = calculatePartCount(file.fileSize());
            List<AlbumUploadSessionResponse.PartUploadUrl> partUrls = new ArrayList<>();
            for (int partNumber = 1; partNumber <= partCount; partNumber++) {
                String partUrl = s3DirectUploadService.presignUploadPart(objectKey, uploadId, partNumber, PRESIGN_EXPIRY);
                partUrls.add(AlbumUploadSessionResponse.PartUploadUrl.of(partNumber, partUrl));
            }
            sessionFiles.add(AlbumUploadSessionFile.video(
                    index, file.fileName(), file.contentType(), file.fileSize(), objectKey, uploadId, partCount));
            targets.add(AlbumUploadSessionResponse.FileUploadTarget.video(index, objectKey, PART_SIZE_BYTES, partUrls));
        }

        albumUploadSessionService.saveWaitingSession(sessionId, currentMember.getId(), sessionFiles);

        log.info("앨범 업로드 세션 발급: sessionId={}, memberId={}, files={}",
                sessionId, currentMember.getId(), sessionFiles.size());
        return AlbumUploadSessionResponse.of(sessionId, PRESIGN_EXPIRY.toSeconds(), targets);
    }

    @Override
    public AlbumUploadAcceptedResponse completeUpload(String sessionId, AlbumUploadCompleteRequest request) {
        Member currentMember = memberUtil.getCurrentMember();
        AlbumUploadSession session = albumUploadSessionService.getSession(sessionId);

        if (!session.isOwnedBy(currentMember.getId())) {
            throw new BusinessException(ErrorCode.ALBUM_UPLOAD_SESSION_FORBIDDEN);
        }
        if (session.isCompleted()) {
            return AlbumUploadAcceptedResponse.from(session.getAlbumId());
        }

        // 동시 완료 요청이 각각 앨범을 생성하지 않도록 세션 단위 락으로 한 요청만 진행한다
        if (!albumUploadSessionService.tryAcquireCompletionLock(sessionId)) {
            AlbumUploadSession latestSession = albumUploadSessionService.getSession(sessionId);
            if (latestSession.isCompleted()) {
                return AlbumUploadAcceptedResponse.from(latestSession.getAlbumId());
            }
            throw new BusinessException(ErrorCode.ALBUM_UPLOAD_ALREADY_IN_PROGRESS);
        }

        try {
            return completeUploadExclusively(sessionId, session, currentMember, request);
        } catch (Exception e) {
            albumUploadSessionService.releaseCompletionLock(sessionId);
            throw e;
        }
    }

    private AlbumUploadAcceptedResponse completeUploadExclusively(String sessionId, AlbumUploadSession session,
                                                                  Member currentMember,
                                                                  AlbumUploadCompleteRequest request) {
        Map<Integer, List<S3DirectUploadService.PartETag>> partsByIndex = toPartsByIndex(request);
        validateVideoParts(session, partsByIndex);

        boolean hasVideos = !session.getVideoFiles().isEmpty();
        List<String> copiedPhotoUrls = new ArrayList<>();
        Long albumId;
        try {
            completeMultipartUploads(session, partsByIndex);
            verifyUploadedObjects(session);

            AlbumMediaLists mediaLists = buildMediaLists(session, copiedPhotoUrls);
            albumId = albumService.saveAlbum(currentMember, request.content(),
                    mediaLists.mediaUrls(), mediaLists.thumbnailUrls(), mediaLists.durations(), hasVideos);
        } catch (Exception e) {
            cleanupOnFailure(session, copiedPhotoUrls);
            if (e instanceof BusinessException businessException) {
                throw businessException;
            }
            log.error("앨범 직접 업로드 완료 처리 실패: sessionId={}, error={}", sessionId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        // 완료 기록 없이 202를 반환하면 락 만료 후 같은 세션이 앨범을 중복 생성할 수 있다
        // → 기록 실패 시 방금 만든 앨범을 보상 삭제하고 실패로 응답한다 (재시도는 multipart가 이미 완료돼 INCOMPLETE로 거부됨)
        try {
            albumUploadSessionService.markCompleted(session, albumId);
        } catch (Exception e) {
            log.error("세션 완료 기록 실패, 앨범 보상 삭제: sessionId={}, albumId={}, error={}",
                    sessionId, albumId, e.getMessage(), e);
            compensateAlbumCreation(session, copiedPhotoUrls, albumId);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        if (hasVideos) {
            try {
                albumVideoProcessingService.processStagedVideosAsync(
                        albumId, currentMember.getId(), toStagedEntries(session));
            } catch (TaskRejectedException e) {
                // 영상 실행기가 가득 차면 앨범·스테이징 객체·세션을 정리하고 재시도를 안내한다
                log.warn("영상 처리 대기열 포화로 업로드 거절: sessionId={}, albumId={}", sessionId, albumId);
                compensateAlbumCreation(session, copiedPhotoUrls, albumId);
                deleteSessionQuietly(sessionId);
                throw new BusinessException(ErrorCode.TEMPORARILY_BUSY);
            } catch (Exception e) {
                log.error("영상 비동기 처리 제출 실패, 앨범 보상 삭제: sessionId={}, albumId={}, error={}",
                        sessionId, albumId, e.getMessage(), e);
                compensateAlbumCreation(session, copiedPhotoUrls, albumId);
                deleteSessionQuietly(sessionId);
                throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
            }
        }

        log.info("앨범 직접 업로드 완료 접수: sessionId={}, albumId={}, hasVideos={}", sessionId, albumId, hasVideos);
        return AlbumUploadAcceptedResponse.from(albumId);
    }

    private void compensateAlbumCreation(AlbumUploadSession session, List<String> copiedPhotoUrls, Long albumId) {
        albumService.deleteAlbumBySystem(albumId);
        cleanupOnFailure(session, copiedPhotoUrls);
    }

    private void deleteSessionQuietly(String sessionId) {
        try {
            albumUploadSessionService.deleteSession(sessionId);
        } catch (Exception e) {
            log.warn("업로드 세션 삭제 실패: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    private Map<Integer, List<S3DirectUploadService.PartETag>> toPartsByIndex(AlbumUploadCompleteRequest request) {
        if (request.files() == null) {
            return Map.of();
        }

        Map<Integer, List<S3DirectUploadService.PartETag>> partsByIndex = new HashMap<>();
        for (AlbumUploadCompleteRequest.CompletedFile file : request.files()) {
            List<S3DirectUploadService.PartETag> parts = file.parts().stream()
                    .map(part -> new S3DirectUploadService.PartETag(part.partNumber(), part.eTag()))
                    .toList();
            partsByIndex.put(file.index(), parts);
        }
        return partsByIndex;
    }

    private void validateVideoParts(AlbumUploadSession session,
                                    Map<Integer, List<S3DirectUploadService.PartETag>> partsByIndex) {
        for (AlbumUploadSessionFile video : session.getVideoFiles()) {
            List<S3DirectUploadService.PartETag> parts = partsByIndex.get(video.getIndex());
            if (parts == null || parts.size() != video.getPartCount()) {
                throw new BusinessException(ErrorCode.ALBUM_UPLOAD_INCOMPLETE);
            }
        }
    }

    private void completeMultipartUploads(AlbumUploadSession session,
                                          Map<Integer, List<S3DirectUploadService.PartETag>> partsByIndex) {
        for (AlbumUploadSessionFile video : session.getVideoFiles()) {
            try {
                s3DirectUploadService.completeMultipartUpload(
                        video.getObjectKey(), video.getUploadId(), partsByIndex.get(video.getIndex()));
            } catch (SdkException e) {
                log.error("multipart 업로드 완료 실패: key={}, error={}", video.getObjectKey(), e.getMessage());
                throw new BusinessException(ErrorCode.ALBUM_UPLOAD_INCOMPLETE);
            }
        }
    }

    private void verifyUploadedObjects(AlbumUploadSession session) {
        for (AlbumUploadSessionFile file : session.getFiles()) {
            S3DirectUploadService.ObjectMetadata metadata = s3DirectUploadService.headObject(file.getObjectKey())
                    .orElseThrow(() -> new BusinessException(ErrorCode.ALBUM_UPLOAD_FILE_MISMATCH));

            if (metadata.contentLength() != file.getFileSize()) {
                throw new BusinessException(ErrorCode.ALBUM_UPLOAD_FILE_MISMATCH, "파일 크기가 선언한 값과 다릅니다.");
            }
            if (!file.getContentType().equals(metadata.contentType())) {
                throw new BusinessException(ErrorCode.ALBUM_UPLOAD_FILE_MISMATCH, "파일 타입이 선언한 값과 다릅니다.");
            }
        }
    }

    private AlbumMediaLists buildMediaLists(AlbumUploadSession session, List<String> copiedPhotoUrls) {
        List<AlbumUploadSessionFile> orderedFiles = session.getFiles().stream()
                .sorted(Comparator.comparingInt(AlbumUploadSessionFile::getIndex))
                .toList();

        List<String> mediaUrls = new ArrayList<>();
        List<String> thumbnailUrls = new ArrayList<>();
        List<Integer> durations = new ArrayList<>();

        for (AlbumUploadSessionFile file : orderedFiles) {
            if (file.isVideo()) {
                mediaUrls.add("");
                thumbnailUrls.add(null);
                durations.add(null);
                continue;
            }

            String finalKey = s3Service.generateFilePath(
                    ALBUM_PHOTO_PREFIX + "/" + session.getMemberId(), file.getFileName());
            String photoUrl = s3DirectUploadService.copyObject(file.getObjectKey(), finalKey);
            copiedPhotoUrls.add(photoUrl);
            s3DirectUploadService.deleteObject(file.getObjectKey());

            mediaUrls.add(photoUrl);
            thumbnailUrls.add(photoUrl); // 사진은 원본이 곧 썸네일 (null 저장 시 @ElementCollection 드롭됨)
            durations.add(null);
        }
        return new AlbumMediaLists(mediaUrls, thumbnailUrls, durations);
    }

    private List<AlbumVideoProcessingService.StagedVideoEntry> toStagedEntries(AlbumUploadSession session) {
        return session.getVideoFiles().stream()
                .sorted(Comparator.comparingInt(AlbumUploadSessionFile::getIndex))
                .map(file -> new AlbumVideoProcessingService.StagedVideoEntry(
                        file.getIndex(), file.getObjectKey(), file.getFileName(), file.getContentType()))
                .toList();
    }

    private void cleanupOnFailure(AlbumUploadSession session, List<String> copiedPhotoUrls) {
        for (AlbumUploadSessionFile file : session.getFiles()) {
            if (file.isVideo()) {
                abortMultipartQuietly(file);
            }
            deleteObjectQuietly(file.getObjectKey());
        }
        for (String photoUrl : copiedPhotoUrls) {
            s3Service.deleteFile(photoUrl);
        }
    }

    private void abortMultipartQuietly(AlbumUploadSessionFile file) {
        try {
            s3DirectUploadService.abortMultipartUpload(file.getObjectKey(), file.getUploadId());
        } catch (Exception e) {
            log.warn("multipart 업로드 중단 실패: key={}, error={}", file.getObjectKey(), e.getMessage());
        }
    }

    private void deleteObjectQuietly(String objectKey) {
        try {
            s3DirectUploadService.deleteObject(objectKey);
        } catch (Exception e) {
            log.warn("스테이징 객체 삭제 실패: key={}, error={}", objectKey, e.getMessage());
        }
    }

    private String generateStagingKey(Long memberId, String sessionId, int index, String fileName) {
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        return String.format("%s/%d/%s/%d_%s%s",
                STAGING_PREFIX, memberId, sessionId, index, uuid, extensionSuffix(fileName));
    }

    private String extensionSuffix(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.')).toLowerCase();
    }

    private int calculatePartCount(long fileSize) {
        return (int) ((fileSize + PART_SIZE_BYTES - 1) / PART_SIZE_BYTES);
    }

    private record AlbumMediaLists(List<String> mediaUrls, List<String> thumbnailUrls, List<Integer> durations) {
    }
}
