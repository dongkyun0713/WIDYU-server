package com.widyu.album.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.widyu.album.AlbumUploadSession;
import com.widyu.album.AlbumUploadSessionFile;
import com.widyu.album.MediaType;
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
import com.widyu.member.MemberType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("AlbumUploadSessionFacadeImpl 단위 테스트")
class AlbumUploadSessionFacadeImplTest {

    private static final String PHOTO_STAGING_KEY = "albums/staging/1/session-1/0_abc.jpg";
    private static final String VIDEO_STAGING_KEY = "albums/staging/1/session-1/1_def.mp4";

    @Mock private AlbumUploadSessionService albumUploadSessionService;
    @Mock private AlbumService albumService;
    @Mock private AlbumVideoProcessingService albumVideoProcessingService;
    @Mock private AlbumMediaPolicy mediaPolicy;
    @Mock private S3DirectUploadService s3DirectUploadService;
    @Mock private S3Service s3Service;
    @Mock private MemberUtil memberUtil;

    @InjectMocks
    private AlbumUploadSessionFacadeImpl albumUploadSessionFacade;

    @Test
    @DisplayName("세션을 발급하면 이미지는 단건 URL, 영상은 파트별 URL을 반환하고 세션을 저장한다")
    void 세션을_발급하면_파일별_presigned_URL을_반환한다() {
        // given
        Member member = memberWithId(1L);
        given(memberUtil.getCurrentMember()).willReturn(member);
        given(s3DirectUploadService.presignPut(anyString(), eq("image/jpeg"), eq(1048576L), any()))
                .willReturn("https://put-url");
        given(s3DirectUploadService.createMultipartUpload(anyString(), eq("video/mp4")))
                .willReturn("upload-id");
        given(s3DirectUploadService.presignUploadPart(anyString(), eq("upload-id"), anyInt(), any()))
                .willReturn("https://part-url");

        AlbumUploadSessionCreateRequest request = new AlbumUploadSessionCreateRequest(List.of(
                new AlbumUploadSessionCreateRequest.FileMetadata("photo.jpg", "image/jpeg", 1048576L),
                new AlbumUploadSessionCreateRequest.FileMetadata("video.mp4", "video/mp4", 25L * 1024 * 1024)
        ));

        // when
        AlbumUploadSessionResponse response = albumUploadSessionFacade.createUploadSession(request);

        // then
        assertThat(response.sessionId()).isNotBlank();
        assertThat(response.expiresInSeconds()).isEqualTo(3600L);
        assertThat(response.files()).hasSize(2);
        assertThat(response.files().get(0).mediaType()).isEqualTo(MediaType.PHOTO);
        assertThat(response.files().get(0).uploadUrl()).isEqualTo("https://put-url");
        assertThat(response.files().get(1).mediaType()).isEqualTo(MediaType.VIDEO);
        assertThat(response.files().get(1).parts()).hasSize(3);
        assertThat(response.files().get(1).partSizeBytes()).isEqualTo(10L * 1024 * 1024);

        ArgumentCaptor<List<AlbumUploadSessionFile>> filesCaptor = ArgumentCaptor.forClass(List.class);
        then(albumUploadSessionService).should().saveWaitingSession(anyString(), eq(1L), filesCaptor.capture());
        List<AlbumUploadSessionFile> savedFiles = filesCaptor.getValue();
        assertThat(savedFiles.get(0).getObjectKey()).startsWith("albums/staging/1/");
        assertThat(savedFiles.get(1).getUploadId()).isEqualTo("upload-id");
        assertThat(savedFiles.get(1).getPartCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("정책 검증에 실패하면 세션을 발급하지 않는다")
    void 정책_검증에_실패하면_세션을_발급하지_않는다() {
        // given
        given(memberUtil.getCurrentMember()).willReturn(memberWithId(1L));
        willThrow(new BusinessException(ErrorCode.FILE_TOO_LARGE)).given(mediaPolicy).validateMetadata(anyList());

        AlbumUploadSessionCreateRequest request = new AlbumUploadSessionCreateRequest(List.of(
                new AlbumUploadSessionCreateRequest.FileMetadata("video.mp4", "video/mp4", 3L * 1024 * 1024 * 1024)
        ));

        // when & then
        assertThatThrownBy(() -> albumUploadSessionFacade.createUploadSession(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_TOO_LARGE);
        then(s3DirectUploadService).shouldHaveNoInteractions();
        then(albumUploadSessionService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("다른 회원의 세션으로 완료 요청하면 ALBUM_UPLOAD_SESSION_FORBIDDEN 예외를 던진다")
    void 다른_회원의_세션으로_완료_요청하면_예외가_발생한다() {
        // given
        given(memberUtil.getCurrentMember()).willReturn(memberWithId(1L));
        AlbumUploadSession session = AlbumUploadSession.createWaiting("session-1", 2L, List.of(photoSessionFile()));
        given(albumUploadSessionService.getSession("session-1")).willReturn(session);

        // when & then
        assertThatThrownBy(() -> albumUploadSessionFacade.completeUpload("session-1", completeRequest(null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALBUM_UPLOAD_SESSION_FORBIDDEN);
        then(albumService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("이미 완료된 세션으로 완료 요청하면 저장된 albumId를 반환한다")
    void 완료된_세션으로_다시_요청하면_같은_albumId를_반환한다() {
        // given
        given(memberUtil.getCurrentMember()).willReturn(memberWithId(1L));
        AlbumUploadSession completed = AlbumUploadSession.createWaiting("session-1", 1L, List.of(photoSessionFile()))
                .complete(55L);
        given(albumUploadSessionService.getSession("session-1")).willReturn(completed);

        // when
        AlbumUploadAcceptedResponse response = albumUploadSessionFacade.completeUpload("session-1", completeRequest(null));

        // then
        assertThat(response.albumId()).isEqualTo(55L);
        then(s3DirectUploadService).shouldHaveNoInteractions();
        then(albumService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("영상 파트 정보가 누락되면 락을 해제하고 ALBUM_UPLOAD_INCOMPLETE 예외를 던진다")
    void 영상_파트_정보가_누락되면_예외가_발생한다() {
        // given
        given(memberUtil.getCurrentMember()).willReturn(memberWithId(1L));
        AlbumUploadSession session = AlbumUploadSession.createWaiting("session-1", 1L, List.of(videoSessionFile()));
        given(albumUploadSessionService.getSession("session-1")).willReturn(session);
        given(albumUploadSessionService.tryAcquireCompletionLock("session-1")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> albumUploadSessionFacade.completeUpload("session-1", completeRequest(null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALBUM_UPLOAD_INCOMPLETE);
        then(s3DirectUploadService).should(never()).completeMultipartUpload(anyString(), anyString(), anyList());
        then(albumUploadSessionService).should().releaseCompletionLock("session-1");
    }

    @Test
    @DisplayName("완료 락 획득에 실패했는데 먼저 진행된 요청이 완료됐으면 같은 albumId를 반환한다")
    void 락_획득_실패_후_완료된_세션이면_같은_albumId를_반환한다() {
        // given
        given(memberUtil.getCurrentMember()).willReturn(memberWithId(1L));
        AlbumUploadSession waiting = AlbumUploadSession.createWaiting("session-1", 1L, List.of(photoSessionFile()));
        AlbumUploadSession completed = waiting.complete(55L);
        given(albumUploadSessionService.getSession("session-1")).willReturn(waiting, completed);
        given(albumUploadSessionService.tryAcquireCompletionLock("session-1")).willReturn(false);

        // when
        AlbumUploadAcceptedResponse response = albumUploadSessionFacade.completeUpload("session-1", completeRequest(null));

        // then
        assertThat(response.albumId()).isEqualTo(55L);
        then(albumService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("완료 락 획득에 실패했고 아직 진행 중이면 ALBUM_UPLOAD_ALREADY_IN_PROGRESS 예외를 던진다")
    void 락_획득_실패_후_진행_중이면_예외가_발생한다() {
        // given
        given(memberUtil.getCurrentMember()).willReturn(memberWithId(1L));
        AlbumUploadSession waiting = AlbumUploadSession.createWaiting("session-1", 1L, List.of(photoSessionFile()));
        given(albumUploadSessionService.getSession("session-1")).willReturn(waiting);
        given(albumUploadSessionService.tryAcquireCompletionLock("session-1")).willReturn(false);

        // when & then
        assertThatThrownBy(() -> albumUploadSessionFacade.completeUpload("session-1", completeRequest(null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALBUM_UPLOAD_ALREADY_IN_PROGRESS);
        then(albumService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("영상 포함 업로드를 완료하면 PROCESSING 앨범을 저장하고 비동기 처리를 시작한다")
    void 영상_포함_업로드를_완료하면_앨범을_저장하고_비동기_처리를_시작한다() {
        // given
        Member member = memberWithId(1L);
        given(memberUtil.getCurrentMember()).willReturn(member);
        AlbumUploadSession session = AlbumUploadSession.createWaiting(
                "session-1", 1L, List.of(photoSessionFile(), videoSessionFile()));
        given(albumUploadSessionService.getSession("session-1")).willReturn(session);
        given(albumUploadSessionService.tryAcquireCompletionLock("session-1")).willReturn(true);
        given(s3DirectUploadService.headObject(PHOTO_STAGING_KEY))
                .willReturn(Optional.of(new S3DirectUploadService.ObjectMetadata(1024L, "image/jpeg")));
        given(s3DirectUploadService.headObject(VIDEO_STAGING_KEY))
                .willReturn(Optional.of(new S3DirectUploadService.ObjectMetadata(2048L, "video/mp4")));
        given(s3Service.generateFilePath("albums/photos/1", "photo.jpg")).willReturn("albums/photos/1/final.jpg");
        given(s3DirectUploadService.copyObject(PHOTO_STAGING_KEY, "albums/photos/1/final.jpg"))
                .willReturn("https://cdn/albums/photos/1/final.jpg");
        given(albumService.saveAlbum(eq(member), eq("가족 여행"), anyList(), anyList(), anyList(), eq(true)))
                .willReturn(100L);

        AlbumUploadCompleteRequest request = completeRequest(List.of(
                new AlbumUploadCompleteRequest.CompletedFile(1, List.of(
                        new AlbumUploadCompleteRequest.CompletedPart(1, "\"etag-1\"")))
        ));

        // when
        AlbumUploadAcceptedResponse response = albumUploadSessionFacade.completeUpload("session-1", request);

        // then
        assertThat(response.albumId()).isEqualTo(100L);
        then(s3DirectUploadService).should().completeMultipartUpload(
                eq(VIDEO_STAGING_KEY), eq("upload-id"),
                eq(List.of(new S3DirectUploadService.PartETag(1, "\"etag-1\""))));

        ArgumentCaptor<List<String>> mediaUrlsCaptor = ArgumentCaptor.forClass(List.class);
        then(albumService).should().saveAlbum(eq(member), eq("가족 여행"),
                mediaUrlsCaptor.capture(), anyList(), anyList(), eq(true));
        assertThat(mediaUrlsCaptor.getValue()).containsExactly("https://cdn/albums/photos/1/final.jpg", "");

        then(albumUploadSessionService).should().markCompleted(session, 100L);
        ArgumentCaptor<List<AlbumVideoProcessingService.StagedVideoEntry>> stagedCaptor =
                ArgumentCaptor.forClass(List.class);
        then(albumVideoProcessingService).should().processStagedVideosAsync(eq(100L), eq(1L), stagedCaptor.capture());
        assertThat(stagedCaptor.getValue()).containsExactly(
                new AlbumVideoProcessingService.StagedVideoEntry(1, VIDEO_STAGING_KEY, "video.mp4", "video/mp4"));
        then(s3DirectUploadService).should().deleteObject(PHOTO_STAGING_KEY);
    }

    @Test
    @DisplayName("세션 완료 기록에 실패하면 앨범을 보상 삭제하고 예외를 던진다")
    void 세션_완료_기록에_실패하면_앨범을_보상_삭제한다() {
        // given
        Member member = memberWithId(1L);
        given(memberUtil.getCurrentMember()).willReturn(member);
        AlbumUploadSession session = AlbumUploadSession.createWaiting("session-1", 1L, List.of(photoSessionFile()));
        given(albumUploadSessionService.getSession("session-1")).willReturn(session);
        given(albumUploadSessionService.tryAcquireCompletionLock("session-1")).willReturn(true);
        given(s3DirectUploadService.headObject(PHOTO_STAGING_KEY))
                .willReturn(Optional.of(new S3DirectUploadService.ObjectMetadata(1024L, "image/jpeg")));
        given(s3Service.generateFilePath("albums/photos/1", "photo.jpg")).willReturn("albums/photos/1/final.jpg");
        given(s3DirectUploadService.copyObject(PHOTO_STAGING_KEY, "albums/photos/1/final.jpg"))
                .willReturn("https://cdn/albums/photos/1/final.jpg");
        given(albumService.saveAlbum(eq(member), any(), anyList(), anyList(), anyList(), eq(false)))
                .willReturn(200L);
        willThrow(new RuntimeException("redis down"))
                .given(albumUploadSessionService).markCompleted(session, 200L);

        // when & then
        assertThatThrownBy(() -> albumUploadSessionFacade.completeUpload("session-1", completeRequest(null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_UPLOAD_FAILED);
        then(albumService).should().deleteAlbumBySystem(200L);
        then(s3Service).should().deleteFile("https://cdn/albums/photos/1/final.jpg");
        then(albumUploadSessionService).should().releaseCompletionLock("session-1");
    }

    @Test
    @DisplayName("영상 비동기 처리 제출에 실패하면 앨범을 보상 삭제하고 세션을 삭제한다")
    void 비동기_처리_제출에_실패하면_앨범과_세션을_정리한다() {
        // given
        Member member = memberWithId(1L);
        given(memberUtil.getCurrentMember()).willReturn(member);
        AlbumUploadSession session = AlbumUploadSession.createWaiting("session-1", 1L, List.of(videoSessionFile()));
        given(albumUploadSessionService.getSession("session-1")).willReturn(session);
        given(albumUploadSessionService.tryAcquireCompletionLock("session-1")).willReturn(true);
        given(s3DirectUploadService.headObject(VIDEO_STAGING_KEY))
                .willReturn(Optional.of(new S3DirectUploadService.ObjectMetadata(2048L, "video/mp4")));
        given(albumService.saveAlbum(eq(member), any(), anyList(), anyList(), anyList(), eq(true)))
                .willReturn(300L);
        willThrow(new RuntimeException("executor rejected"))
                .given(albumVideoProcessingService).processStagedVideosAsync(eq(300L), eq(1L), anyList());

        AlbumUploadCompleteRequest request = completeRequest(List.of(
                new AlbumUploadCompleteRequest.CompletedFile(1, List.of(
                        new AlbumUploadCompleteRequest.CompletedPart(1, "\"etag-1\"")))
        ));

        // when & then
        assertThatThrownBy(() -> albumUploadSessionFacade.completeUpload("session-1", request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_UPLOAD_FAILED);
        then(albumService).should().deleteAlbumBySystem(300L);
        then(s3DirectUploadService).should().deleteObject(VIDEO_STAGING_KEY);
        then(albumUploadSessionService).should().deleteSession("session-1");
        then(albumUploadSessionService).should().releaseCompletionLock("session-1");
    }

    @Test
    @DisplayName("영상 실행기가 가득 차 제출이 거절되면 앨범·스테이징·세션을 정리하고 재시도 가능한 503 예외를 던진다")
    void 영상_실행기가_가득_차면_정리하고_재시도_가능한_예외가_발생한다() {
        // given
        Member member = memberWithId(1L);
        given(memberUtil.getCurrentMember()).willReturn(member);
        AlbumUploadSession session = AlbumUploadSession.createWaiting("session-1", 1L, List.of(videoSessionFile()));
        given(albumUploadSessionService.getSession("session-1")).willReturn(session);
        given(albumUploadSessionService.tryAcquireCompletionLock("session-1")).willReturn(true);
        given(s3DirectUploadService.headObject(VIDEO_STAGING_KEY))
                .willReturn(Optional.of(new S3DirectUploadService.ObjectMetadata(2048L, "video/mp4")));
        given(albumService.saveAlbum(eq(member), any(), anyList(), anyList(), anyList(), eq(true)))
                .willReturn(300L);
        willThrow(new TaskRejectedException("album-video saturated"))
                .given(albumVideoProcessingService).processStagedVideosAsync(eq(300L), eq(1L), anyList());

        AlbumUploadCompleteRequest request = completeRequest(List.of(
                new AlbumUploadCompleteRequest.CompletedFile(1, List.of(
                        new AlbumUploadCompleteRequest.CompletedPart(1, "\"etag-1\"")))
        ));

        // when & then
        assertThatThrownBy(() -> albumUploadSessionFacade.completeUpload("session-1", request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TEMPORARILY_BUSY);
        assertThat(ErrorCode.TEMPORARILY_BUSY.getHttpStatus().value()).isEqualTo(503);
        then(albumService).should().deleteAlbumBySystem(300L);
        then(s3DirectUploadService).should().deleteObject(VIDEO_STAGING_KEY);
        then(albumUploadSessionService).should().deleteSession("session-1");
        then(albumUploadSessionService).should().releaseCompletionLock("session-1");
    }

    @Test
    @DisplayName("업로드된 파일 크기가 선언과 다르면 스테이징을 정리하고 예외를 던진다")
    void 파일_크기가_선언과_다르면_스테이징을_정리하고_예외가_발생한다() {
        // given
        given(memberUtil.getCurrentMember()).willReturn(memberWithId(1L));
        AlbumUploadSession session = AlbumUploadSession.createWaiting("session-1", 1L, List.of(videoSessionFile()));
        given(albumUploadSessionService.getSession("session-1")).willReturn(session);
        given(albumUploadSessionService.tryAcquireCompletionLock("session-1")).willReturn(true);
        given(s3DirectUploadService.headObject(VIDEO_STAGING_KEY))
                .willReturn(Optional.of(new S3DirectUploadService.ObjectMetadata(9999L, "video/mp4")));

        AlbumUploadCompleteRequest request = completeRequest(List.of(
                new AlbumUploadCompleteRequest.CompletedFile(1, List.of(
                        new AlbumUploadCompleteRequest.CompletedPart(1, "\"etag-1\"")))
        ));

        // when & then
        assertThatThrownBy(() -> albumUploadSessionFacade.completeUpload("session-1", request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALBUM_UPLOAD_FILE_MISMATCH);
        then(s3DirectUploadService).should().abortMultipartUpload(VIDEO_STAGING_KEY, "upload-id");
        then(s3DirectUploadService).should().deleteObject(VIDEO_STAGING_KEY);
        then(albumService).shouldHaveNoInteractions();
        then(albumVideoProcessingService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("이미지 전용 업로드를 완료하면 비동기 처리 없이 앨범을 저장한다")
    void 이미지_전용_업로드를_완료하면_비동기_처리를_호출하지_않는다() {
        // given
        Member member = memberWithId(1L);
        given(memberUtil.getCurrentMember()).willReturn(member);
        AlbumUploadSession session = AlbumUploadSession.createWaiting("session-1", 1L, List.of(photoSessionFile()));
        given(albumUploadSessionService.getSession("session-1")).willReturn(session);
        given(albumUploadSessionService.tryAcquireCompletionLock("session-1")).willReturn(true);
        given(s3DirectUploadService.headObject(PHOTO_STAGING_KEY))
                .willReturn(Optional.of(new S3DirectUploadService.ObjectMetadata(1024L, "image/jpeg")));
        given(s3Service.generateFilePath("albums/photos/1", "photo.jpg")).willReturn("albums/photos/1/final.jpg");
        given(s3DirectUploadService.copyObject(PHOTO_STAGING_KEY, "albums/photos/1/final.jpg"))
                .willReturn("https://cdn/albums/photos/1/final.jpg");
        given(albumService.saveAlbum(eq(member), any(), anyList(), anyList(), anyList(), eq(false)))
                .willReturn(200L);

        // when
        AlbumUploadAcceptedResponse response = albumUploadSessionFacade.completeUpload("session-1", completeRequest(null));

        // then
        assertThat(response.albumId()).isEqualTo(200L);
        then(albumVideoProcessingService).shouldHaveNoInteractions();
        then(albumUploadSessionService).should().markCompleted(session, 200L);
    }

    private Member memberWithId(Long id) {
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01011112222");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    private AlbumUploadSessionFile photoSessionFile() {
        return AlbumUploadSessionFile.photo(0, "photo.jpg", "image/jpeg", 1024L, PHOTO_STAGING_KEY);
    }

    private AlbumUploadSessionFile videoSessionFile() {
        return AlbumUploadSessionFile.video(1, "video.mp4", "video/mp4", 2048L, VIDEO_STAGING_KEY, "upload-id", 1);
    }

    private AlbumUploadCompleteRequest completeRequest(List<AlbumUploadCompleteRequest.CompletedFile> files) {
        return new AlbumUploadCompleteRequest("가족 여행", files);
    }
}
