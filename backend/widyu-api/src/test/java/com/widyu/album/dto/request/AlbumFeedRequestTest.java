package com.widyu.album.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AlbumFeedRequest 커서 파서 단위 테스트")
class AlbumFeedRequestTest {

    @Test
    @DisplayName("복합 커서를 전달하면 createdAt과 albumId를 파싱한다")
    void 복합_커서를_전달하면_createdAt과_albumId를_파싱한다() {
        // given
        String cursor = "2024-12-21T14:30:00|123";
        String date = "2024-12-21";

        // when
        AlbumFeedRequest request = AlbumFeedRequest.from(cursor, date);

        // then
        assertThat(request.lastCreatedAt()).isEqualTo(LocalDateTime.of(2024, 12, 21, 14, 30));
        assertThat(request.lastAlbumId()).isEqualTo(123L);
        assertThat(request.date()).isEqualTo(date);
        assertThat(request.hasCursor()).isTrue();
    }

    @Test
    @DisplayName("커서가 없으면 첫 페이지 요청으로 파싱한다")
    void 커서가_없으면_첫_페이지_요청으로_파싱한다() {
        // given
        String date = "2024-12-21";

        // when
        AlbumFeedRequest nullCursorRequest = AlbumFeedRequest.from(null, date);
        AlbumFeedRequest blankCursorRequest = AlbumFeedRequest.from(" ", date);

        // then
        assertThat(nullCursorRequest.hasCursor()).isFalse();
        assertThat(blankCursorRequest.hasCursor()).isFalse();
        assertThat(nullCursorRequest.date()).isEqualTo(date);
        assertThat(blankCursorRequest.date()).isEqualTo(date);
    }

    @Test
    @DisplayName("날짜 형식이 잘못되면 BusinessException이 발생한다")
    void 날짜_형식이_잘못되면_BusinessException이_발생한다() {
        // given
        AlbumFeedRequest request = AlbumFeedRequest.from(null, "2024-13-40");

        // when & then
        assertThatThrownBy(request::parsedDate)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("날짜 형식은 yyyy-MM-dd 이어야 합니다.");
    }

    @Test
    @DisplayName("커서 형식이 잘못되면 BAD_REQUEST 예외를 던진다")
    void 커서_형식이_잘못되면_BAD_REQUEST_예외를_던진다() {
        // given
        String cursor = "123";

        // when & then
        assertThatThrownBy(() -> AlbumFeedRequest.from(cursor, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("커서 날짜나 앨범 ID를 파싱할 수 없으면 BAD_REQUEST 예외를 던진다")
    void 커서_날짜나_앨범_ID를_파싱할_수_없으면_BAD_REQUEST_예외를_던진다() {
        // when & then
        assertThatThrownBy(() -> AlbumFeedRequest.from("invalid-date|123", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);

        assertThatThrownBy(() -> AlbumFeedRequest.from("2024-12-21T14:30:00|abc", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }
}
