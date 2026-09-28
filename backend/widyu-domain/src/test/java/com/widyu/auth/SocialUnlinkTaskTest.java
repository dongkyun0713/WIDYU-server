package com.widyu.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SocialUnlinkTask 단위 테스트")
class SocialUnlinkTaskTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 12, 0);

    @Test
    @DisplayName("연동 해제 작업을 만들면 10분 뒤를 다음 시도 시각으로 둔 대기 상태가 된다")
    void 작업을_만들면_10분_뒤를_다음_시도_시각으로_둔_대기_상태가_된다() {
        // when
        SocialUnlinkTask task = SocialUnlinkTask.pending(1L, "apple", "apple-id", "token", NOW);

        // then
        assertThat(task.getStatus()).isEqualTo(SocialUnlinkTaskStatus.PENDING);
        assertThat(task.getNextRetryAt()).isEqualTo(NOW.plusMinutes(10));
        assertThat(task.getAttemptCount()).isZero();
    }

    @Test
    @DisplayName("연동 해제에 성공하면 완료되고 oauthId와 토큰을 지운다")
    void 연동_해제에_성공하면_완료되고_oauthId와_토큰을_지운다() {
        // given
        SocialUnlinkTask task = SocialUnlinkTask.pending(1L, "apple", "apple-id", "token", NOW);

        // when
        task.complete(NOW);

        // then
        assertThat(task.getStatus()).isEqualTo(SocialUnlinkTaskStatus.COMPLETED);
        assertThat(task.getCompletedAt()).isEqualTo(NOW);
        assertThat(task.getOauthId()).isNull();
        assertThat(task.getRefreshToken()).isNull();
    }

    @Test
    @DisplayName("연동 해제에 실패하면 시도 횟수를 올리고 10분 뒤 다시 시도하도록 대기 상태를 유지한다")
    void 연동_해제에_실패하면_시도_횟수를_올리고_대기_상태를_유지한다() {
        // given
        SocialUnlinkTask task = SocialUnlinkTask.pending(1L, "kakao", "kakao-id", null, NOW);

        // when
        task.fail("BusinessException", NOW);

        // then
        assertThat(task.getStatus()).isEqualTo(SocialUnlinkTaskStatus.PENDING);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getLastErrorType()).isEqualTo("BusinessException");
        assertThat(task.getNextRetryAt()).isEqualTo(NOW.plusMinutes(10));
        assertThat(task.getOauthId()).isEqualTo("kakao-id");
    }

    @Test
    @DisplayName("다섯 번째로 실패하면 최종 실패로 끝내고 oauthId와 토큰을 지운다")
    void 다섯_번째로_실패하면_최종_실패로_끝내고_oauthId와_토큰을_지운다() {
        // given
        SocialUnlinkTask task = SocialUnlinkTask.pending(1L, "naver", "naver-id", "token", NOW);
        for (int i = 0; i < 4; i++) {
            task.fail("BusinessException", NOW);
        }

        // when
        task.fail("BusinessException", NOW);

        // then
        assertThat(task.getStatus()).isEqualTo(SocialUnlinkTaskStatus.FAILED);
        assertThat(task.getAttemptCount()).isEqualTo(5);
        assertThat(task.getFailedAt()).isEqualTo(NOW);
        assertThat(task.getOauthId()).isNull();
        assertThat(task.getRefreshToken()).isNull();
    }
}
