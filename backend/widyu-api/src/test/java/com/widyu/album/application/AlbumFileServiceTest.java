package com.widyu.album.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.global.infrastructure.video.VideoCompressionService;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
@DisplayName("AlbumFileService 예외 처리 단위 테스트")
class AlbumFileServiceTest {

    @Mock private S3Service s3Service;
    @Mock private VideoCompressionService videoCompressionService;
    @Mock private AlbumMediaPolicy mediaPolicy;

    @InjectMocks
    private AlbumFileService albumFileService;

    @Test
    @DisplayName("영상 처리를 시작하지 못한 업로드를 버리면 올린 사진과 영상 임시 파일을 지운다")
    void 시작하지_못한_업로드를_버리면_사진과_임시_파일을_지운다() {
        // given
        File videoTemp = new File("source_video.mp4");
        AlbumFileService.AsyncUploadPreparation prep = new AlbumFileService.AsyncUploadPreparation(
                List.of("https://cdn/photo.jpg", ""),
                java.util.Arrays.asList("https://cdn/photo.jpg", null),
                java.util.Arrays.asList(null, null),
                List.of(new AlbumVideoProcessingService.VideoEntry(1, videoTemp, "video.mp4", "video/mp4")));

        // when
        albumFileService.discardAsyncUpload(prep);

        // then
        then(s3Service).should().deleteFile("https://cdn/photo.jpg");
        then(s3Service).should(never()).deleteFile("");
        then(videoCompressionService).should().cleanupTempFile(videoTemp);
    }

    @Test
    @DisplayName("미디어 업로드 중 BusinessException이 발생하면 원본 errorCode를 보존한다")
    void 미디어_업로드_중_비즈니스_예외는_원본_errorCode를_보존한다() {
        // given
        List<MultipartFile> files = List.of(imageFile());
        given(s3Service.generateFilePath(anyString(), anyString())).willReturn("albums/photos/1/photo.jpg");
        given(s3Service.uploadFile(files.get(0), "albums/photos/1/photo.jpg"))
                .willThrow(new BusinessException(ErrorCode.INVALID_FILE_TYPE));

        // when & then
        assertThatThrownBy(() -> albumFileService.uploadMediaFiles(files, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_FILE_TYPE)
                .hasMessageContaining("지원하지 않는 파일 형식입니다.");
    }

    @Test
    @DisplayName("두 번째 파일 업로드 실패 시 이미 업로드된 파일을 정리한다")
    void 두번째_파일_업로드_실패_시_업로드된_파일을_정리한다() {
        // given
        MultipartFile first = imageFile("first.jpg");
        MultipartFile second = imageFile("second.jpg");
        List<MultipartFile> files = List.of(first, second);

        given(s3Service.generateFilePath(anyString(), anyString()))
                .willReturn("albums/photos/1/first.jpg")
                .willReturn("albums/photos/1/second.jpg");
        given(s3Service.uploadFile(first, "albums/photos/1/first.jpg")).willReturn("https://cdn/first.jpg");
        given(s3Service.uploadFile(second, "albums/photos/1/second.jpg"))
                .willThrow(new BusinessException(ErrorCode.FILE_UPLOAD_FAILED));

        // when & then
        assertThatThrownBy(() -> albumFileService.uploadMediaFiles(files, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_UPLOAD_FAILED)
                .hasMessageContaining("파일 업로드에 실패했습니다.");
        then(s3Service).should().deleteFile("https://cdn/first.jpg");
    }

    @Test
    @DisplayName("썸네일 업로드가 실패하면 이미 업로드된 영상 파일을 정리한다")
    void 썸네일_업로드_실패_시_업로드된_영상_파일을_정리한다() throws IOException {
        // given
        MultipartFile video = videoFile();
        File thumbnail = Files.createTempFile("thumbnail_", ".jpg").toFile();
        Files.writeString(thumbnail.toPath(), "thumbnail");

        given(videoCompressionService.needsCompression(video)).willReturn(false);
        given(videoCompressionService.extractDuration(any(File.class))).willReturn(10);
        given(videoCompressionService.generateThumbnail(any(File.class), anyDouble())).willReturn(thumbnail);
        given(s3Service.generateFilePath(anyString(), anyString()))
                .willReturn("albums/videos/1/video.mp4")
                .willReturn("albums/thumbnails/1/video_thumbnail.jpg");
        given(s3Service.uploadFile(any(MultipartFile.class), anyString()))
                .willReturn("https://cdn/video.mp4")
                .willThrow(new BusinessException(ErrorCode.FILE_UPLOAD_FAILED));

        // when & then
        assertThatThrownBy(() -> albumFileService.uploadAlbumVideoWithThumbnail(video, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_UPLOAD_FAILED);
        then(s3Service).should().deleteFile("https://cdn/video.mp4");
    }

    @Test
    @DisplayName("업로드 파일 정리 실패 시 예외를 덮어쓰지 않는다")
    void 업로드_파일_정리_실패_시_예외를_던지지_않는다() {
        // given
        willThrow(new RuntimeException("delete failed"))
                .given(s3Service)
                .deleteFile("https://cdn/video.mp4");

        // when & then
        albumFileService.cleanupUploadedFiles(List.of("https://cdn/video.mp4"));
    }

    @Test
    @DisplayName("썸네일 파일이 이미지가 아니면 INVALID_FILE_TYPE 예외를 던지고 업로드하지 않는다")
    void 썸네일_파일이_이미지가_아니면_예외가_발생한다() {
        // given
        MockMultipartFile thumbnail = new MockMultipartFile(
                "thumbnail", "thumbnail.txt", "text/plain", "text".getBytes());

        // when & then
        assertThatThrownBy(() -> albumFileService.uploadThumbnail(thumbnail, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_FILE_TYPE)
                .hasMessageContaining("지원하지 않는 파일 형식입니다.");
        then(s3Service).should(never()).uploadFile(org.mockito.ArgumentMatchers.any(), anyString());
    }

    private MultipartFile imageFile() {
        return imageFile("photo.jpg");
    }

    private MultipartFile imageFile(String filename) {
        return new MockMultipartFile("mediaFiles", filename, "image/jpeg", "image".getBytes());
    }

    private MultipartFile videoFile() {
        return new MockMultipartFile("mediaFiles", "video.mp4", "video/mp4", "video".getBytes());
    }
}
