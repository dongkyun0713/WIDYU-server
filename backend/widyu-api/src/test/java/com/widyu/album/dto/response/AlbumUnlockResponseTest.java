package com.widyu.album.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.widyu.album.Album;
import com.widyu.album.AlbumUnlock;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AlbumUnlockResponse 단위 테스트")
class AlbumUnlockResponseTest {

    @Test
    @DisplayName("캡션이 없으면 앨범 제목을 null로 반환한다")
    void 캡션이_없으면_앨범_제목을_null로_반환한다() {
        // given
        AlbumUnlock unlock = unlockOf(null);

        // when
        AlbumUnlockResponse response = AlbumUnlockResponse.from(unlock, 50L);

        // then
        assertThat(response.albumTitle()).isNull();
    }

    @Test
    @DisplayName("캡션이 50자를 넘으면 앞 50자에 말줄임표를 붙여 반환한다")
    void 캡션이_50자를_넘으면_앞_50자에_말줄임표를_붙여_반환한다() {
        // given
        AlbumUnlock unlock = unlockOf("가".repeat(51));

        // when
        AlbumUnlockResponse response = AlbumUnlockResponse.from(unlock, 50L);

        // then
        assertThat(response.albumTitle()).isEqualTo("가".repeat(50) + "...");
    }

    private AlbumUnlock unlockOf(String content) {
        Member guardian = Member.createMember(MemberType.GUARDIAN, "보호자", "01011112222");
        Member senior = Member.createMember(MemberType.SENIOR, "시니어", "01033334444");
        Album album = Album.createAlbumWithMetadata(guardian, content, List.of(), List.of(), List.of());
        return AlbumUnlock.createUnlock(album, senior);
    }
}
