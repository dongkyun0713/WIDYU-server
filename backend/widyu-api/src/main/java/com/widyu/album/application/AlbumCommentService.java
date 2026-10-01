package com.widyu.album.application;

import com.widyu.album.dto.request.AlbumCommentCreateRequest;
import com.widyu.album.dto.request.AlbumCommentUpdateRequest;
import com.widyu.album.dto.response.AlbumCommentResponse;
import com.widyu.album.Album;
import com.widyu.album.AlbumComment;
import com.widyu.album.repository.AlbumCommentRepository;
import com.widyu.album.repository.AlbumRepository;
import com.widyu.fcm.event.album.dto.AlbumCommentedEvent;
import com.widyu.member.Member;
import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AlbumCommentService {

    private final AlbumCommentRepository albumCommentRepository;
    private final AlbumRepository albumRepository;
    private final MemberUtil memberUtil;
    private final ApplicationEventPublisher eventPublisher;
    private final AlbumPermissionService albumPermissionService;

    @Transactional
    public AlbumCommentResponse createComment(Long albumId, AlbumCommentCreateRequest request) {
        Member currentMember = memberUtil.getCurrentMember();

        Album album = albumRepository.findByIdAndStatus(albumId, Status.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ALBUM_NOT_FOUND));
        albumPermissionService.checkFamilyAccess(album, currentMember);

        AlbumComment comment;
        if (request.parentCommentId() != null) {
            AlbumComment parentComment = albumCommentRepository.findByIdAndStatus(request.parentCommentId(), Status.ACTIVE)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ALBUM_COMMENT_NOT_FOUND));

            if (!parentComment.getAlbum().getId().equals(albumId)) {
                throw new BusinessException(ErrorCode.ALBUM_COMMENT_PARENT_ALBUM_MISMATCH);
            }

            if (parentComment.getDepth() >= 1) {
                throw new BusinessException(ErrorCode.ALBUM_COMMENT_DEPTH_EXCEEDED);
            }

            comment = AlbumComment.createReply(album, currentMember, parentComment, request.content());
        } else {
            comment = AlbumComment.createComment(album, currentMember, request.content());
        }

        AlbumComment savedComment = albumCommentRepository.save(comment);

        // 앨범 댓글 수 증가
        album.incrementCommentCount();

        // 댓글 작성 알림 이벤트 발행
        eventPublisher.publishEvent(new AlbumCommentedEvent(
                albumId,
                currentMember.getId(),
                album.getMember().getId(),
                savedComment.getId(),
                request.parentCommentId()
        ));

        return AlbumCommentResponse.fromWithoutReplies(savedComment);
    }


    @Transactional
    public AlbumCommentResponse updateComment(Long commentId, AlbumCommentUpdateRequest request) {
        Member currentMember = memberUtil.getCurrentMember();

        AlbumComment comment = albumCommentRepository.findByIdAndStatus(commentId, Status.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ALBUM_COMMENT_NOT_FOUND));

        if (!comment.getMember().getId().equals(currentMember.getId())) {
            throw new BusinessException(ErrorCode.ALBUM_COMMENT_NOT_OWNER);
        }

        comment.updateContent(request.content());

        return AlbumCommentResponse.fromWithoutReplies(comment);
    }

    @Transactional
    public void deleteComment(Long commentId) {
        Member currentMember = memberUtil.getCurrentMember();

        AlbumComment comment = albumCommentRepository.findByIdAndStatus(commentId, Status.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ALBUM_COMMENT_NOT_FOUND));

        if (!comment.getMember().getId().equals(currentMember.getId())) {
            throw new BusinessException(ErrorCode.ALBUM_COMMENT_NOT_OWNER);
        }

        // 삭제될 댓글 수 계산 (본인 + 활성 대댓글들)
        long deletedCount = 1 + comment.getReplies().stream()
                .filter(reply -> reply.getStatus() == Status.ACTIVE)
                .count();
        
        comment.deleteWithReplies();
        
        // 앨범 댓글 수 감소
        Album album = comment.getAlbum();
        for (int i = 0; i < deletedCount; i++) {
            album.decrementCommentCount();
        }

    }
}
