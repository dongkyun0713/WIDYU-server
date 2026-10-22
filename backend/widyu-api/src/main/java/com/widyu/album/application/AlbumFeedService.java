package com.widyu.album.application;

import com.widyu.album.dto.request.AlbumFeedRequest;
import com.widyu.album.dto.response.AlbumFeedResponse;
import com.widyu.album.dto.response.AlbumMediaResponse;
import com.widyu.album.Album;
import com.widyu.album.repository.AlbumLikeRepository;
import com.widyu.album.repository.AlbumRepository;
import com.widyu.album.repository.AlbumUnlockRepository;
import com.widyu.album.repository.AlbumViewRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.application.FamilyAccessService;
import com.widyu.global.dto.CursorPage;
import com.widyu.global.util.MemberUtil;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlbumFeedService {

    private static final int ALBUM_FEED_SIZE = 10;
    private static final int MEDIA_FEED_SIZE = 10;

    private final AlbumRepository albumRepository;
    private final AlbumLikeRepository albumLikeRepository;
    private final AlbumViewRepository albumViewRepository;
    private final AlbumUnlockRepository albumUnlockRepository;
    private final MemberUtil memberUtil;
    private final FamilyAccessService familyAccessService;

    @Transactional(readOnly = true)
    public CursorPage<AlbumFeedResponse> getAlbumFeed(AlbumFeedRequest request) {
        final Member currentMember = memberUtil.getCurrentMember();
        List<Long> familyMemberIds = familyAccessService.getFamilyMemberIds(currentMember);
        LocalDateTime lastCreatedAt = null;
        Long lastAlbumId = null;
        LocalDate date = null;
        if (request.hasCursor()) {
            lastCreatedAt = request.lastCreatedAt();
            lastAlbumId = request.lastAlbumId();
        }
        if (request.hasDate()) {
            date = request.parsedDate();
        }

        // 1) 앨범 ID 커서 페이지 조회. Slice가 size+1건을 읽어 hasNext를 정하고 내용은 size건만 준다.
        Pageable pageable = PageRequest.of(0, ALBUM_FEED_SIZE);
        Slice<Long> idSlice = findAlbumIdSlice(
                lastCreatedAt,
                lastAlbumId,
                date,
                familyMemberIds,
                pageable);

        // 2) 상세 조회 (ID 순서 보존)
        List<Long> albumIds = idSlice.getContent();
        List<Album> albums = findAlbumsOrderedByIds(albumIds);

        // 3) 벌크 변환
        List<AlbumFeedResponse> feed = convertToFeedResponsesBulk(albums, currentMember);

        // 4) 커서/hasNext
        boolean hasNext = idSlice.hasNext();
        String nextCursor = lastCursorOrNull(feed);

        return new CursorPage<>(feed, nextCursor, hasNext);
    }

    @Transactional(readOnly = true)
    public CursorPage<AlbumMediaResponse> getMediaFeed(LocalDateTime lastCreatedAt, Long lastPostId) {
        Member currentMember = memberUtil.getCurrentMember();
        List<Long> familyMemberIds = familyAccessService.getFamilyMemberIds(currentMember);
        Pageable pageable = PageRequest.of(0, MEDIA_FEED_SIZE);

        // 1) 앨범 ID 커서 페이지
        Slice<Long> idSlice = findAlbumIdSlice(lastCreatedAt, lastPostId, null, familyMemberIds, pageable);

        // 2) 상세 조회 (ID 순서 보존)
        List<Long> albumIds = idSlice.getContent();
        List<Album> albums = findAlbumsOrderedByIds(albumIds);

        // 3) 앨범 -> 미디어 평탄화
        List<AlbumMediaResponse> albumMediaResponses = new ArrayList<>();
        for (Album album : albums) {
            albumMediaResponses.addAll(AlbumMediaResponse.fromAlbum(album));
        }

        // 4) 커서는 마지막 앨범의 (createdAt, albumId)
        boolean hasNext = idSlice.hasNext();
        String nextCursor = null;
        if (!albumMediaResponses.isEmpty()) {
            AlbumMediaResponse last = albumMediaResponses.getLast();
            nextCursor = buildCursor(last.createdAt(), last.postId());
        }

        return new CursorPage<>(albumMediaResponses, nextCursor, hasNext);
    }

    private Slice<Long> findAlbumIdSlice(LocalDateTime lastCreatedAt, Long lastPostId, LocalDate date,
                                         List<Long> familyMemberIds, Pageable pageable) {
        if (date != null) {
            LocalDateTime startOfDay = date.atStartOfDay();
            LocalDateTime startOfNextDay = date.plusDays(1).atStartOfDay();
            if (lastCreatedAt != null) {
                return albumRepository.findAlbumIdsAfterPostIdByDateAndMemberIds(
                        lastCreatedAt, lastPostId, startOfDay, startOfNextDay, familyMemberIds, pageable);
            }
            return albumRepository.findLatestAlbumIdsByDateAndMemberIds(
                    startOfDay, startOfNextDay, familyMemberIds, pageable);
        }
        if (lastCreatedAt != null) {
            return albumRepository.findAlbumIdsAfterPostIdAndMemberIds(
                    lastCreatedAt, lastPostId, familyMemberIds, pageable);
        }
        return albumRepository.findLatestAlbumIdsByMemberIds(familyMemberIds, pageable);
    }

    private String buildCursor(LocalDateTime createdAt, Long albumId) {
        return createdAt.toString() + "|" + albumId;
    }

    private List<Album> findAlbumsOrderedByIds(List<Long> albumIds) {
        if (albumIds == null || albumIds.isEmpty()) return List.of();

        Map<Long, Integer> order = new HashMap<>(albumIds.size());
        for (int i = 0; i < albumIds.size(); i++) {
            order.put(albumIds.get(i), i);
        }

        List<Album> fetched = albumRepository.findAlbumsWithCollectionsByIds(albumIds);
        // 순서 보존 정렬
        fetched.sort(Comparator.comparingInt(a -> order.getOrDefault(a.getId(), Integer.MAX_VALUE)));
        return fetched;
    }

    private String lastCursorOrNull(List<AlbumFeedResponse> feed) {
        if (feed == null || feed.isEmpty()) return null;
        AlbumFeedResponse last = feed.getLast();
        return buildCursor(last.createdAt(), last.albumId());
    }

    private List<AlbumFeedResponse> convertToFeedResponsesBulk(List<Album> albums, Member currentMember) {
        if (albums == null || albums.isEmpty()) return List.of();

        List<Long> albumIds = albums.stream()
                .map(Album::getId)
                .collect(Collectors.toList());

        // 보호자는 잠금 대상이 아니라 해금 조회 없이 전부 열람 가능하다
        boolean viewerExemptFromLock = currentMember.getType() == MemberType.GUARDIAN;
        Set<Long> unlockedAlbumIds = findUnlockedAlbumIds(albumIds, currentMember);

        Map<Long, List<AlbumFeedResponse.ViewerInfo>> viewersMap = albumViewRepository
                .findTop3ViewersForAlbums(albumIds).stream()
                .collect(Collectors.groupingBy(
                        view -> view.getAlbum().getId(),
                        Collectors.mapping(
                                view -> AlbumFeedResponse.ViewerInfo.from(view.getMember()),
                                Collectors.toList()
                        )
                ));

        // 순서대로 응답 생성
        List<AlbumFeedResponse> result = new ArrayList<>(albums.size());
        for (Album album : albums) {
            boolean canEdit = album.getMember().getId().equals(currentMember.getId());
            boolean isUnlocked = viewerExemptFromLock
                    || !album.requiresUnlock()
                    || unlockedAlbumIds.contains(album.getId());
            List<AlbumFeedResponse.ViewerInfo> viewers =
                    viewersMap.getOrDefault(album.getId(), List.of());
            result.add(AlbumFeedResponse.from(album, canEdit, isUnlocked, viewers));
        }
        return result;
    }

    // 보호자는 잠금 대상이 아니므로 해금 기록을 조회하지 않는다.
    private Set<Long> findUnlockedAlbumIds(List<Long> albumIds, Member currentMember) {
        if (currentMember.getType() == MemberType.GUARDIAN) {
            return Set.of();
        }
        return Set.copyOf(albumUnlockRepository.findUnlockedAlbumIdsByMemberAndAlbumIds(currentMember, albumIds));
    }
}
