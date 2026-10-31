package com.widyu.album.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.widyu.album.Album;
import com.widyu.album.repository.AlbumCalendarRepository;
import com.widyu.album.repository.AlbumViewRepository;
import com.widyu.global.entity.Status;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import com.widyu.member.application.FamilyAccessService;
import com.widyu.album.dto.FamilyAlbumPageResponse;
import com.widyu.album.dto.FamilyAlbumResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("AlbumCalendarService 단위 테스트")
class AlbumCalendarServiceTest {

    @Mock private AlbumCalendarRepository albumRepository;
    @Mock private AlbumViewRepository albumViewRepository;
    @Mock private MemberUtil memberUtil;
    @Mock private FamilyAccessService familyAccessService;
    @Mock private AlbumPermissionService albumPermissionService;

    @InjectMocks
    private AlbumCalendarService albumCalendarService;

    @Test
    @DisplayName("앨범 캘린더를 조회하면 가족의 앨범이 있는 날짜를 반환한다")
    void 앨범_캘린더를_조회하면_가족의_앨범이_있는_날짜를_반환한다() {
        // given
        Member currentMember = org.mockito.Mockito.mock(Member.class);
        Album firstAlbum = org.mockito.Mockito.mock(Album.class);
        Album secondAlbum = org.mockito.Mockito.mock(Album.class);
        Album duplicateDayAlbum = org.mockito.Mockito.mock(Album.class);
        List<Long> familyMemberIds = List.of(1L, 2L, 3L);
        LocalDateTime start = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 1, 0, 0);

        given(memberUtil.getCurrentMember()).willReturn(currentMember);
        given(familyAccessService.getFamilyMemberIds(currentMember)).willReturn(familyMemberIds);
        given(albumRepository.findAllByMemberIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanAndStatus(
                familyMemberIds, start, end, Status.ACTIVE
        )).willReturn(List.of(firstAlbum, secondAlbum, duplicateDayAlbum));
        given(firstAlbum.getCreatedAt()).willReturn(LocalDateTime.of(2026, 8, 10, 12, 0));
        given(secondAlbum.getCreatedAt()).willReturn(LocalDateTime.of(2026, 8, 2, 12, 0));
        given(duplicateDayAlbum.getCreatedAt()).willReturn(LocalDateTime.of(2026, 8, 10, 18, 0));

        // when
        List<Integer> days = albumCalendarService.getDaysWithEvents(2026, 8);

        // then
        assertThat(days).containsExactly(2, 10);
        then(albumRepository).should()
                .findAllByMemberIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanAndStatus(
                        familyMemberIds, start, end, Status.ACTIVE
                );
    }

    @Test
    @DisplayName("날짜별 가족 앨범을 조회하면 잠금 판정에 따라 원본 URL을 빼고 잠금 여부를 담는다")
    void 날짜별_가족_앨범은_잠금_판정에_따라_원본_URL을_뺀다() {
        // given
        Member currentMember = org.mockito.Mockito.mock(Member.class);
        given(currentMember.getId()).willReturn(1L);
        Member author = org.mockito.Mockito.mock(Member.class);
        Album lockedAlbum = Album.createAlbumWithMetadata(author, "잠김",
                new ArrayList<>(List.of("https://cdn/a.mp4")),
                new ArrayList<>(List.of("https://cdn/a_thumbnail.jpg")),
                new ArrayList<>(List.of(10)));
        Album unlockedAlbum = Album.createAlbumWithMetadata(author, "해금",
                new ArrayList<>(List.of("https://cdn/b.mp4")),
                new ArrayList<>(List.of("https://cdn/b_thumbnail.jpg")),
                new ArrayList<>(List.of(10)));
        ReflectionTestUtils.setField(lockedAlbum, "id", 20L);
        ReflectionTestUtils.setField(unlockedAlbum, "id", 21L);
        LocalDate date = LocalDate.of(2026, 10, 31);

        given(memberUtil.getCurrentMember()).willReturn(currentMember);
        given(albumRepository.findFamilyAlbumsByDateRangeWithCursor(
                1L, date.atStartOfDay(), date.atTime(LocalTime.MAX), null, 6, Status.ACTIVE))
                .willReturn(List.of(lockedAlbum, unlockedAlbum));
        given(albumViewRepository.findMembersByAlbumId(anyLong())).willReturn(List.of());
        given(albumPermissionService.isUnlockedFor(lockedAlbum, currentMember)).willReturn(false);
        given(albumPermissionService.isUnlockedFor(unlockedAlbum, currentMember)).willReturn(true);

        // when
        FamilyAlbumPageResponse response = albumCalendarService.getFamilyAlbumsByDateWithCursor(date, null);

        // then
        FamilyAlbumResponse locked = response.albums().get(0);
        FamilyAlbumResponse unlocked = response.albums().get(1);
        assertThat(locked.isUnlocked()).isFalse();
        assertThat(locked.mediaUrls()).isEmpty();
        assertThat(locked.thumbnailUrls()).containsExactly("https://cdn/a_thumbnail.jpg");
        assertThat(unlocked.isUnlocked()).isTrue();
        assertThat(unlocked.mediaUrls()).containsExactly("https://cdn/b.mp4");
    }
}
