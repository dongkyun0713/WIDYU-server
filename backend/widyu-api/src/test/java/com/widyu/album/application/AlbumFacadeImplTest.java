package com.widyu.album.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import com.widyu.album.dto.request.AlbumUploadRequest;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("AlbumFacadeImpl 단위 테스트")
class AlbumFacadeImplTest {

    @Mock private AlbumFeedService albumFeedService;
    @Mock private AlbumService albumService;
    @Mock private AlbumLikeService albumLikeService;
    @Mock private AlbumUnlockService albumUnlockService;
    @Mock private AlbumFileService albumFileService;
    @Mock private AlbumMediaPolicy mediaPolicy;
    @Mock private AlbumVideoProcessingService albumVideoProcessingService;
    @Mock private MemberUtil memberUtil;

    @InjectMocks
    private AlbumFacadeImpl albumFacade;

    @Test
    @DisplayName("영상 실행기가 가득 차 제출이 거절되면 앨범과 업로드 준비물을 지우고 재시도 가능한 503 예외를 던진다")
    void 영상_실행기가_가득_차면_앨범과_준비물을_지우고_재시도_가능한_예외가_발생한다() {
        // given
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01011112222");
        ReflectionTestUtils.setField(member, "id", 1L);
        given(memberUtil.getCurrentMember()).willReturn(member);
        AlbumUploadRequest request = new AlbumUploadRequest("가족 여행", List.of(
                new MockMultipartFile("mediaFiles", "video.mp4", "video/mp4", new byte[]{1})));
        AlbumFileService.AsyncUploadPreparation prep = new AlbumFileService.AsyncUploadPreparation(
                List.of(""), Arrays.asList((String) null), Arrays.asList((Integer) null),
                List.of(new AlbumVideoProcessingService.VideoEntry(0, new File("source.mp4"), "video.mp4", "video/mp4")));
        given(albumFileService.prepareForAsyncUpload(request.mediaFiles(), 1L)).willReturn(prep);
        given(albumService.saveAlbum(eq(member), any(), anyList(), anyList(), anyList(), eq(true))).willReturn(10L);
        willThrow(new TaskRejectedException("album-video saturated"))
                .given(albumVideoProcessingService).processVideosAsync(10L, 1L, prep.videoEntries());

        // when & then
        assertThatThrownBy(() -> albumFacade.uploadAlbum(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TEMPORARILY_BUSY);
        then(albumService).should().deleteAlbumBySystem(10L);
        then(albumFileService).should().discardAsyncUpload(prep);
    }
}
