package com.widyu.album.application;

import com.widyu.album.dto.FamilyAlbumPageResponse;
import com.widyu.album.dto.FamilyAlbumResponse;
import com.widyu.album.Album;
import com.widyu.album.repository.AlbumCalendarRepository;
import com.widyu.album.repository.AlbumViewRepository;
import com.widyu.member.Member;
import com.widyu.member.application.FamilyAccessService;
import com.widyu.global.entity.Status;
import com.widyu.global.util.MemberUtil;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AlbumCalendarService {

    private final AlbumCalendarRepository albumRepository;
    private final AlbumViewRepository albumViewRepository;
    private final MemberUtil memberUtil;
    private final FamilyAccessService familyAccessService;
    private final AlbumPermissionService albumPermissionService;

    public List<Integer> getDaysWithEvents(int year, int month) {
        Member member = memberUtil.getCurrentMember();
        List<Long> familyMemberIds = familyAccessService.getFamilyMemberIds(member);
        YearMonth yearMonth = YearMonth.of(year, month);
        LocalDateTime start = yearMonth.atDay(1).atStartOfDay();
        LocalDateTime end = yearMonth.plusMonths(1).atDay(1).atStartOfDay();

        return albumRepository.findAllByMemberIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanAndStatus(
                        familyMemberIds, start, end, Status.ACTIVE)
                .stream()
                .map(album -> album.getCreatedAt().getDayOfMonth())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public FamilyAlbumPageResponse getFamilyAlbumsByDateWithCursor(LocalDate date, Long cursor) {
        Member member = memberUtil.getCurrentMember();
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.atTime(LocalTime.MAX);

        int pageSize = 5;
        int fetchSize = pageSize + 1;

        List<Album> albums = albumRepository.findFamilyAlbumsByDateRangeWithCursor(
                member.getId(), start, end, cursor, fetchSize, Status.ACTIVE);

        if (albums.isEmpty()) {
            return FamilyAlbumPageResponse.empty();
        }

        boolean hasNext = albums.size() > pageSize;
        List<Album> pageAlbums = albums;
        if (hasNext) {
            pageAlbums = albums.subList(0, pageSize);
        }

        List<FamilyAlbumResponse> albumResponses = pageAlbums.stream()
                .map(album -> {
                    List<Member> viewers = albumViewRepository.findMembersByAlbumId(album.getId());
                    boolean isUnlocked = albumPermissionService.isUnlockedFor(album, member);
                    return FamilyAlbumResponse.from(album, viewers, isUnlocked);
                })
                .collect(Collectors.toList());

        Long nextCursor = null;
        if (hasNext) {
            nextCursor = pageAlbums.get(pageSize - 1).getId();
        }

        return FamilyAlbumPageResponse.of(albumResponses, hasNext, nextCursor);
    }
}
