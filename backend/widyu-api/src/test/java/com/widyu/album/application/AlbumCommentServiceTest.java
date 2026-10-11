package com.widyu.album.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.widyu.album.Album;
import com.widyu.album.AlbumComment;
import com.widyu.album.dto.request.AlbumCommentCreateRequest;
import com.widyu.album.dto.request.AlbumCommentUpdateRequest;
import com.widyu.album.repository.AlbumCommentRepository;
import com.widyu.album.repository.AlbumRepository;
import com.widyu.fcm.event.album.dto.AlbumCommentedEvent;
import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("AlbumCommentService 단위 테스트")
class AlbumCommentServiceTest {

    @Mock private AlbumCommentRepository albumCommentRepository;
    @Mock private AlbumRepository albumRepository;
    @Mock private MemberUtil memberUtil;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AlbumPermissionService albumPermissionService;

    @InjectMocks
    private AlbumCommentService albumCommentService;

    @Test
    @DisplayName("최상위 댓글 작성 시 댓글이 저장되고 앨범 댓글 수가 증가한다")
    void 최상위_댓글_작성_시_저장되고_댓글수_증가() {
        // given
        Member currentMember = mock(Member.class);
        given(currentMember.getId()).willReturn(1L);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);

        Member albumOwner = mock(Member.class);
        given(albumOwner.getId()).willReturn(2L);

        Album album = mock(Album.class);
        given(album.getMember()).willReturn(albumOwner);
        given(albumRepository.findByIdAndStatus(10L, Status.ACTIVE)).willReturn(Optional.of(album));
        allowFamilyAccess(album, currentMember);

        AlbumComment savedComment = mock(AlbumComment.class);
        given(savedComment.getId()).willReturn(100L);
        given(savedComment.getContent()).willReturn("좋은 사진이에요");
        given(savedComment.getAlbum()).willReturn(album);
        given(savedComment.getMember()).willReturn(currentMember);
        given(currentMember.getName()).willReturn("홍길동");
        given(albumCommentRepository.save(any(AlbumComment.class))).willReturn(savedComment);

        AlbumCommentCreateRequest request = new AlbumCommentCreateRequest("좋은 사진이에요", null);

        // when
        albumCommentService.createComment(10L, request);

        // then
        verify(albumCommentRepository).save(any(AlbumComment.class));
        verify(album).incrementCommentCount();
        ArgumentCaptor<AlbumCommentedEvent> eventCaptor = ArgumentCaptor.forClass(AlbumCommentedEvent.class);
        then(eventPublisher).should().publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().commentId()).isEqualTo(100L);
        assertThat(eventCaptor.getValue().parentCommentId()).isNull();
    }

    @Test
    @DisplayName("대댓글 작성 시 댓글이 저장되고 앨범 댓글 수가 증가한다")
    void 대댓글_작성_시_저장되고_댓글수_증가() {
        // given
        Member currentMember = mock(Member.class);
        given(currentMember.getId()).willReturn(1L);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);

        Member albumOwner = mock(Member.class);
        given(albumOwner.getId()).willReturn(2L);

        Album album = mock(Album.class);
        given(album.getId()).willReturn(10L);
        given(album.getMember()).willReturn(albumOwner);
        given(albumRepository.findByIdAndStatus(10L, Status.ACTIVE)).willReturn(Optional.of(album));
        allowFamilyAccess(album, currentMember);

        AlbumComment parentComment = mock(AlbumComment.class);
        given(parentComment.getAlbum()).willReturn(album);
        given(parentComment.getDepth()).willReturn(0);
        given(albumCommentRepository.findByIdAndStatus(50L, Status.ACTIVE)).willReturn(Optional.of(parentComment));

        AlbumComment savedReply = mock(AlbumComment.class);
        given(savedReply.getId()).willReturn(101L);
        given(savedReply.getContent()).willReturn("동의해요!");
        given(savedReply.getAlbum()).willReturn(album);
        given(savedReply.getMember()).willReturn(currentMember);
        given(currentMember.getName()).willReturn("홍길동");
        given(albumCommentRepository.save(any(AlbumComment.class))).willReturn(savedReply);

        AlbumCommentCreateRequest request = new AlbumCommentCreateRequest("동의해요!", 50L);

        // when
        albumCommentService.createComment(10L, request);

        // then
        verify(albumCommentRepository).save(any(AlbumComment.class));
        verify(album).incrementCommentCount();
        ArgumentCaptor<AlbumCommentedEvent> eventCaptor = ArgumentCaptor.forClass(AlbumCommentedEvent.class);
        then(eventPublisher).should().publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().commentId()).isEqualTo(101L);
        assertThat(eventCaptor.getValue().parentCommentId()).isEqualTo(50L);
    }

    @Test
    @DisplayName("depth가 1인 댓글에 대댓글 작성 시도 시 BusinessException을 던진다")
    void depth가_1인_댓글에_대댓글_작성_시_예외가_발생한다() {
        // given
        Member currentMember = mock(Member.class);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);

        Album album = mock(Album.class);
        given(albumRepository.findByIdAndStatus(10L, Status.ACTIVE)).willReturn(Optional.of(album));
        allowFamilyAccess(album, currentMember);

        AlbumComment parentComment = mock(AlbumComment.class);
        given(parentComment.getAlbum()).willReturn(album);
        given(parentComment.getDepth()).willReturn(1);
        given(albumCommentRepository.findByIdAndStatus(50L, Status.ACTIVE)).willReturn(Optional.of(parentComment));

        given(album.getId()).willReturn(10L);

        AlbumCommentCreateRequest request = new AlbumCommentCreateRequest("대대댓글", 50L);

        // when & then
        assertThatThrownBy(() -> albumCommentService.createComment(10L, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALBUM_COMMENT_DEPTH_EXCEEDED);
    }

    @Test
    @DisplayName("본인 댓글 수정 시 댓글 내용이 변경된다")
    void 본인_댓글_수정_시_내용이_변경된다() {
        // given
        Member currentMember = mock(Member.class);
        given(currentMember.getId()).willReturn(1L);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);

        Member commentOwner = mock(Member.class);
        given(commentOwner.getId()).willReturn(1L);
        given(commentOwner.getName()).willReturn("홍길동");

        Album album = mock(Album.class);
        given(album.getId()).willReturn(10L);

        AlbumComment comment = mock(AlbumComment.class);
        given(comment.getMember()).willReturn(commentOwner);
        given(comment.getAlbum()).willReturn(album);
        given(albumCommentRepository.findByIdAndStatus(100L, Status.ACTIVE)).willReturn(Optional.of(comment));

        given(comment.getId()).willReturn(100L);
        given(comment.getContent()).willReturn("수정된 내용");

        // when
        albumCommentService.updateComment(100L, new AlbumCommentUpdateRequest("수정된 내용"));

        // then
        verify(comment).updateContent("수정된 내용");
    }

    @Test
    @DisplayName("타인 댓글 수정 시 BusinessException을 던진다")
    void 타인_댓글_수정_시_예외가_발생한다() {
        // given
        Member currentMember = mock(Member.class);
        given(currentMember.getId()).willReturn(1L);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);

        Member commentOwner = mock(Member.class);
        given(commentOwner.getId()).willReturn(2L);

        AlbumComment comment = mock(AlbumComment.class);
        given(comment.getMember()).willReturn(commentOwner);
        given(albumCommentRepository.findByIdAndStatus(100L, Status.ACTIVE)).willReturn(Optional.of(comment));

        // when & then
        assertThatThrownBy(() -> albumCommentService.updateComment(100L, new AlbumCommentUpdateRequest("수정")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALBUM_COMMENT_NOT_OWNER);
    }

    @Test
    @DisplayName("댓글 삭제 시 댓글과 대댓글이 모두 삭제되고 앨범 댓글 수가 감소한다")
    void 댓글_삭제_시_댓글과_대댓글_삭제되고_댓글수_감소() {
        // given
        Member currentMember = mock(Member.class);
        given(currentMember.getId()).willReturn(1L);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);

        Member commentOwner = mock(Member.class);
        given(commentOwner.getId()).willReturn(1L);

        AlbumComment reply = mock(AlbumComment.class);
        given(reply.getStatus()).willReturn(Status.ACTIVE);

        Album album = mock(Album.class);

        AlbumComment comment = mock(AlbumComment.class);
        given(comment.getMember()).willReturn(commentOwner);
        given(comment.getReplies()).willReturn(List.of(reply));
        given(comment.getAlbum()).willReturn(album);
        given(albumCommentRepository.findByIdAndStatus(100L, Status.ACTIVE)).willReturn(Optional.of(comment));

        // when
        albumCommentService.deleteComment(100L);

        // then - 댓글 1개 + 활성 대댓글 1개 = 총 2번 감소
        verify(comment).deleteWithReplies();
        verify(album, times(2)).decrementCommentCount();
    }

    @Test
    @DisplayName("타인 댓글 삭제 시 BusinessException을 던진다")
    void 타인_댓글_삭제_시_예외가_발생한다() {
        // given
        Member currentMember = mock(Member.class);
        given(currentMember.getId()).willReturn(1L);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);

        Member commentOwner = mock(Member.class);
        given(commentOwner.getId()).willReturn(2L);

        AlbumComment comment = mock(AlbumComment.class);
        given(comment.getMember()).willReturn(commentOwner);
        given(albumCommentRepository.findByIdAndStatus(100L, Status.ACTIVE)).willReturn(Optional.of(comment));

        // when & then
        assertThatThrownBy(() -> albumCommentService.deleteComment(100L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALBUM_COMMENT_NOT_OWNER);
    }

    @Test
    @DisplayName("가족 외 앨범 댓글 작성 시 FORBIDDEN 예외를 던진다")
    void 가족_외_앨범_댓글_작성_시_예외가_발생한다() {
        // given
        Member currentMember = mock(Member.class);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);
        Album album = mock(Album.class);
        given(albumRepository.findByIdAndStatus(10L, Status.ACTIVE)).willReturn(Optional.of(album));
        willThrow(new BusinessException(ErrorCode.FORBIDDEN)).given(albumPermissionService)
                .checkFamilyAccess(album, currentMember);

        // when & then
        assertThatThrownBy(() -> albumCommentService.createComment(10L, new AlbumCommentCreateRequest("댓글", null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    private void allowFamilyAccess(Album album, Member member) {
        willDoNothing().given(albumPermissionService).checkFamilyAccess(album, member);
    }
}
