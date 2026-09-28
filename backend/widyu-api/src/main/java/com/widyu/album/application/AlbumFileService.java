package com.widyu.album.application;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.global.infrastructure.video.VideoCompressionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


@Slf4j
@Service
@RequiredArgsConstructor
public class AlbumFileService {

    private final S3Service s3Service;
    private final VideoCompressionService videoCompressionService;
    private final AlbumMediaPolicy mediaPolicy;

    private static final String ALBUM_PHOTO_PREFIX = "albums/photos";
    private static final String ALBUM_VIDEO_PREFIX = "albums/videos";
    private static final String THUMBNAIL_PREFIX = "albums/thumbnails";

    public static record UploadResult(List<String> mediaUrls, List<String> thumbnailUrls, List<Integer> durations) {
    }

    public static record VideoUploadResult(String videoUrl, String thumbnailUrl, Integer duration) {
    }

    public static record AsyncUploadPreparation(
            List<String> mediaUrls,
            List<String> thumbnailUrls,
            List<Integer> durations,
            List<AlbumVideoProcessingService.VideoEntry> videoEntries
    ) {
        public boolean hasVideos() {
            return !videoEntries.isEmpty();
        }
    }

    /**
     * 단일 이미지 업로드
     */
    public String uploadAlbumPhoto(MultipartFile file, Long memberId) {
        mediaPolicy.validate(List.of(file));

        String directory = ALBUM_PHOTO_PREFIX + "/" + memberId;
        String filePath = s3Service.generateFilePath(directory, safeOriginalName(file));
        return s3Service.uploadFile(file, filePath);
    }

    /**
     * 단일 비디오 업로드(압축 포함, 썸네일/길이 추출 없음)
     */
    public String uploadAlbumVideo(MultipartFile file, Long memberId) {
        mediaPolicy.validate(List.of(file));

        MultipartFile processedFile = file;
        File tempCompressedFile = null;

        try {
            if (videoCompressionService.needsCompression(file)) {
                tempCompressedFile = videoCompressionService.compressVideo(file);
                processedFile = wrapFileAsMultipart(tempCompressedFile, safeOriginalName(file));
            }

            String directory = ALBUM_VIDEO_PREFIX + "/" + memberId;
            String filePath = s3Service.generateFilePath(directory, safeOriginalName(processedFile));
            return s3Service.uploadFile(processedFile, filePath);

        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        } finally {
            cleanupTemp(tempCompressedFile);
        }
    }

    /**
     * 단일 비디오 업로드(압축 포함) + 썸네일 업로드 + 길이 추출
     */
    public VideoUploadResult uploadAlbumVideoWithThumbnail(MultipartFile file, Long memberId) {
        mediaPolicy.validate(List.of(file));

        MultipartFile processedFile = file;
        File tempCompressedFile = null;
        File tempThumbnailFile = null;

        File sourceFile = null;
        boolean ownedSourceFile = false;
        List<String> uploadedUrls = new ArrayList<>();

        try {
            // 1) 필요 시 압축 → 결과 File을 이후 단계에서 직접 재사용
            if (videoCompressionService.needsCompression(file)) {
                log.info("동영상 압축 시작: memberId={}, originalSizeMB={}", memberId, mb(file.getSize()));
                tempCompressedFile = videoCompressionService.compressVideo(file);
                processedFile = wrapFileAsMultipart(tempCompressedFile, safeOriginalName(file));
                log.info("동영상 압축 완료: memberId={}, compressedSizeMB={}", memberId, mb(processedFile.getSize()));
                sourceFile = tempCompressedFile;
            } else {
                // 압축 불필요 시 MultipartFile을 File로 한 번만 변환 (이후 복사 없음)
                sourceFile = toTempFile(file);
                ownedSourceFile = true;
            }

            // 2) sourceFile 기준으로 길이 추출 → 썸네일 생성 (파일 복사 없음)
            int duration = videoCompressionService.extractDuration(sourceFile);
            log.info("동영상 길이 추출 완료: duration={}s", duration);

            log.info("썸네일 생성 시작: memberId={}", memberId);
            tempThumbnailFile = videoCompressionService.generateThumbnail(sourceFile, (double) duration);
            String thumbName = baseName(safeOriginalName(file)) + "_thumbnail.jpg";
            MultipartFile thumbnailPart = wrapFileAsMultipart(tempThumbnailFile, thumbName);

            // 3) 업로드
            String videoDir = ALBUM_VIDEO_PREFIX + "/" + memberId;
            String videoKey = s3Service.generateFilePath(videoDir, safeOriginalName(processedFile));
            String videoUrl = s3Service.uploadFile(processedFile, videoKey);
            uploadedUrls.add(videoUrl);

            String thumbnailUrl = uploadThumbnail(thumbnailPart, memberId);
            uploadedUrls.add(thumbnailUrl);
            log.info("비디오/썸네일 업로드 성공: memberId={}, duration={}s", memberId, duration);

            return new VideoUploadResult(videoUrl, thumbnailUrl, duration);

        } catch (Exception e) {
            cleanupUploadedFiles(uploadedUrls);
            log.error("동영상 처리 실패: memberId={}, errorType={}", memberId, e.getClass().getSimpleName());
            if (e instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        } finally {
            cleanupTemp(tempCompressedFile);
            cleanupTemp(tempThumbnailFile);
            if (ownedSourceFile) {
                cleanupTemp(sourceFile);
            }
        }
    }

    public String uploadThumbnail(MultipartFile file, Long memberId) {
        validateInternalImage(file);

        String directory = THUMBNAIL_PREFIX + "/" + memberId;
        String filePath = s3Service.generateFilePath(directory, safeOriginalName(file));
        return s3Service.uploadFile(file, filePath);
    }

    UploadResult uploadMediaFilesWithThumbnails(List<MultipartFile> mediaFiles, Long memberId) {
        mediaPolicy.validate(mediaFiles);

        List<String> mediaUrls = new ArrayList<>();
        List<String> thumbnailUrls = new ArrayList<>();
        List<Integer> durations = new ArrayList<>();

        try {
            for (MultipartFile file : mediaFiles) {
                String ct = file.getContentType();
                if (ct == null) {
                    throw new BusinessException(ErrorCode.INVALID_FILE_TYPE);
                }

                if (ct.startsWith("image/")) {
                    String url = uploadAlbumPhoto(file, memberId);
                    mediaUrls.add(url);
                    thumbnailUrls.add(url); // 사진은 원본이 곧 썸네일 (null 저장 시 @ElementCollection 드롭됨)
                    durations.add(null);
                    log.debug("이미지 업로드 성공: memberId={}", memberId);
                    continue;
                }

                if (ct.startsWith("video/")) {
                    VideoUploadResult r = uploadAlbumVideoWithThumbnail(file, memberId);
                    mediaUrls.add(r.videoUrl());
                    thumbnailUrls.add(r.thumbnailUrl());
                    durations.add(r.duration());
                    log.debug("비디오 업로드 성공: memberId={}, duration={}", memberId, r.duration());
                    continue;
                }

                throw new BusinessException(ErrorCode.INVALID_FILE_TYPE);
            }

            return new UploadResult(mediaUrls, thumbnailUrls, durations);

        } catch (Exception e) {
            // 사진은 mediaUrls·thumbnailUrls에 같은 URL이 들어가므로 중복 삭제 방지
            java.util.Set<String> uploadedUrls = new java.util.LinkedHashSet<>(mediaUrls);
            uploadedUrls.addAll(thumbnailUrls);
            cleanupUploadedFiles(new ArrayList<>(uploadedUrls));
            if (e instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, e.getMessage());
        }
    }

    public List<String> uploadMediaFiles(List<MultipartFile> mediaFiles, Long memberId) {
        return uploadMediaFilesWithThumbnails(mediaFiles, memberId).mediaUrls();
    }

    public AsyncUploadPreparation prepareForAsyncUpload(List<MultipartFile> mediaFiles, Long memberId) {
        mediaPolicy.validate(mediaFiles);

        List<String> mediaUrls = new ArrayList<>();
        List<String> thumbnailUrls = new ArrayList<>();
        List<Integer> durations = new ArrayList<>();
        List<AlbumVideoProcessingService.VideoEntry> videoEntries = new ArrayList<>();

        List<String> uploadedPhotoUrls = new ArrayList<>();

        try {
            for (int i = 0; i < mediaFiles.size(); i++) {
                MultipartFile file = mediaFiles.get(i);
                String contentType = file.getContentType();

                if (contentType == null) {
                    throw new BusinessException(ErrorCode.INVALID_FILE_TYPE);
                }

                if (contentType.startsWith("image/")) {
                    String url = uploadAlbumPhoto(file, memberId);
                    uploadedPhotoUrls.add(url);
                    mediaUrls.add(url);
                    thumbnailUrls.add(url); // 사진은 원본이 곧 썸네일 (null 저장 시 @ElementCollection 드롭됨)
                    durations.add(null);
                } else if (contentType.startsWith("video/")) {
                    File tempFile = toTempFile(file);
                    videoEntries.add(new AlbumVideoProcessingService.VideoEntry(i, tempFile, file.getOriginalFilename(), contentType));
                    mediaUrls.add("");
                    thumbnailUrls.add(null);
                    durations.add(null);
                } else {
                    throw new BusinessException(ErrorCode.INVALID_FILE_TYPE);
                }
            }
        } catch (Exception e) {
            cleanupUploadedFiles(uploadedPhotoUrls);
            if (e instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        return new AsyncUploadPreparation(mediaUrls, thumbnailUrls, durations, videoEntries);
    }

    private void validateInternalImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_IS_EMPTY);
        }
        String ct = file.getContentType();
        if (ct == null || !ct.startsWith("image/")) {
            throw new BusinessException(ErrorCode.INVALID_FILE_TYPE);
        }
    }

    void cleanupUploadedFiles(List<String> uploadedUrls) {
        if (uploadedUrls == null || uploadedUrls.isEmpty()) {
            return;
        }

        for (String url : uploadedUrls) {
            if (url == null) {
                continue;
            }
            try {
                s3Service.deleteFile(url);
            } catch (Exception ex) {
                log.warn("파일 정리 실패: errorType={}", ex.getClass().getSimpleName());
            }
        }
    }

    File toTempFile(MultipartFile file) throws IOException {
        String ext = guessExtByContentType(file.getContentType());
        Path tmp = Files.createTempFile("source_" + UUID.randomUUID(), extensionSuffix(ext));
        Files.copy(file.getInputStream(), tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return tmp.toFile();
    }

    private void cleanupTemp(File f) {
        if (f == null) {
            return;
        }
        try {
            videoCompressionService.cleanupTempFile(f);
        } catch (Exception ex) {
            log.warn("임시 파일 정리 실패: path={}, error={}", f.getAbsolutePath(), ex.getMessage());
        }
    }

    private String safeOriginalName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            // 확장자 없을 수도 있으니, 타입 기준 fallback
            String ext = guessExtByContentType(file.getContentType());
            return "upload_" + System.currentTimeMillis() + extensionSuffix(ext);
        }
        return name;
    }

    private String baseName(String fileName) {
        if (fileName == null) {
            return "file";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx > 0) {
            return fileName.substring(0, idx);
        }
        return fileName;
    }

    private String extensionSuffix(String ext) {
        if (ext.isEmpty()) {
            return "";
        }
        return "." + ext;
    }

    private String guessExtByContentType(String ct) {
        if (ct == null) {
            return "";
        }
        return switch (ct) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/bmp" -> "bmp";
            case "image/svg+xml" -> "svg";
            case "video/mp4" -> "mp4";
            case "video/quicktime" -> "mov";
            case "video/x-msvideo" -> "avi";
            case "video/x-matroska" -> "mkv";
            case "video/webm" -> "webm";
            case "video/x-flv" -> "flv";
            case "video/x-ms-wmv" -> "wmv";
            default -> "";
        };
    }

    private MultipartFile wrapFileAsMultipart(File file, String originalFileName) throws IOException {
        final Path path = file.toPath();
        final String fileName = resolveFileName(originalFileName, file);
        String probedContentType = Files.probeContentType(path);
        final String contentType = resolveContentType(probedContentType, fileName);

        return new MultipartFile() {
            @NotNull
            @Override
            public String getName() {
                return "file";
            }

            @Override
            public String getOriginalFilename() {
                return fileName;
            }

            @Override
            public String getContentType() {
                return contentType;
            }

            @Override
            public boolean isEmpty() {
                return file.length() == 0;
            }

            @Override
            public long getSize() {
                return file.length();
            }

            @NotNull
            @Override
            public byte[] getBytes() throws IOException { // ⚠️ 대용량 사용 금지(필요 시만)
                return Files.readAllBytes(path);
            }

            @NotNull
            @Override
            public java.io.InputStream getInputStream() throws IOException {
                return new FileInputStream(file);
            }

            @Override
            public void transferTo(@NotNull File dest) throws IOException {
                // 목적에 따라 move/copy 선택
                Files.copy(path, dest.toPath());
            }
        };
    }

    private String detectContentTypeByName(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "application/octet-stream";
        }
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "svg" -> "image/svg+xml";
            case "mp4" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "avi" -> "video/x-msvideo";
            case "mkv" -> "video/x-matroska";
            case "webm" -> "video/webm";
            case "flv" -> "video/x-flv";
            case "wmv" -> "video/x-ms-wmv";
            default -> "application/octet-stream";
        };
    }

    private String resolveFileName(String originalFileName, File file) {
        if (originalFileName != null && !originalFileName.isBlank()) {
            return originalFileName;
        }
        return file.getName();
    }

    private String resolveContentType(String probedContentType, String fileName) {
        if (probedContentType != null) {
            return probedContentType;
        }
        return detectContentTypeByName(fileName);
    }

    private String mb(long bytes) {
        return String.valueOf(bytes / (1024 * 1024));
    }
}
