package com.widyu.album.application;

import com.widyu.album.dto.request.AlbumUpdateRequest;
import com.widyu.album.dto.response.AlbumDetailResponse;
import com.widyu.album.dto.response.AlbumUploadResponse;
import com.widyu.album.Album;
import com.widyu.album.AlbumComment;
import com.widyu.album.repository.AlbumCommentRepository;
import com.widyu.album.repository.AlbumRepository;
import com.widyu.fcm.event.album.dto.AlbumCreatedEvent;
import com.widyu.member.Member;
import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlbumService {

    private final AlbumRepository albumRepository;
    private final AlbumCommentRepository albumCommentRepository;
    private final AlbumViewService albumViewService;
    private final AlbumPermissionService albumPermissionService;
    private final MemberUtil memberUtil;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Long saveAlbum(Member member, String content,
                          List<String> mediaUrls, List<String> thumbnailUrls, List<Integer> durations,
                          boolean hasVideos) {
        Album album;
        if (hasVideos) {
            album = Album.createAlbumForProcessing(member, content, mediaUrls, thumbnailUrls, durations);
        } else {
            album = Album.createAlbumWithMetadata(member, content, mediaUrls, thumbnailUrls, durations);
        }
        Album saved = albumRepository.save(album);

        if (!hasVideos) {
            eventPublisher.publishEvent(new AlbumCreatedEvent(saved.getId(), member.getId()));
        }

        return saved.getId();
    }

    @Transactional
    public AlbumUploadResponse updateAlbum(Long albumId, AlbumUpdateRequest request) {
        Member currentMember = memberUtil.getCurrentMember();

        Album album = albumRepository.findByIdAndStatus(albumId, Status.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ALBUM_NOT_FOUND));

        if (!album.getMember().getId().equals(currentMember.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인의 앨범만 수정할 수 있습니다.");
        }

        album.updateContent(request.content());

        log.info("앨범 수정 완료: albumId={}, memberId={}", albumId, currentMember.getId());
        return AlbumUploadResponse.from(album);
    }

    @Transactional
    public void deleteAlbum(Long albumId) {
        Member currentMember = memberUtil.getCurrentMember();

        Album album = albumRepository.findByIdAndStatus(albumId, Status.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ALBUM_NOT_FOUND));

        if (!album.getMember().getId().equals(currentMember.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인의 앨범만 삭제할 수 있습니다.");
        }

        album.delete();

        log.info("앨범 삭제 완료: albumId={}, memberId={}", albumId, currentMember.getId());
    }

    /**
     * 업로드 후처리 실패 보상용 시스템 삭제 — 소유자 검증 없이 상태와 무관하게 삭제 처리한다
     */
    @Transactional
    public void deleteAlbumBySystem(Long albumId) {
        albumRepository.findById(albumId).ifPresent(Album::delete);
        log.warn("앨범 보상 삭제 처리: albumId={}", albumId);
    }

    @Transactional(readOnly = true)
    public AlbumDetailResponse getAlbumDetail(Long albumId) {
        Member currentMember = memberUtil.getCurrentMember();

        Album album = albumRepository.findByIdAndStatusWithCollections(albumId, Status.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ALBUM_NOT_FOUND));

        albumPermissionService.checkFamilyAccess(album, currentMember);
        // 잠긴 앨범도 해금 화면에 가격을 보여 주도록 응답하되, 해금 전에는 조회로 치지 않는다
        boolean isUnlocked = albumPermissionService.isUnlockedFor(album, currentMember);

        // 조회 기록 추가 (해금된 타인 앨범인 경우에만)
        if (isUnlocked && !album.getMember().getId().equals(currentMember.getId())) {
            albumViewService.recordView(album, currentMember);
        }

        // 댓글 목록 조회 (최상위 댓글만, 대댓글은 응답에서 포함)
        List<AlbumComment> comments = albumCommentRepository.findTopLevelCommentsByAlbumAndStatus(album, Status.ACTIVE);

        // 조회자 목록 조회 (최근 조회자 최대 3명)
        List<Member> viewers = albumViewService.getRecentViewers(album, 3);

        log.info("앨범 상세 조회 완료: albumId={}, memberId={}", albumId, currentMember.getId());

        return AlbumDetailResponse.from(album, currentMember.getId(), viewers, comments, isUnlocked);
    }
}
