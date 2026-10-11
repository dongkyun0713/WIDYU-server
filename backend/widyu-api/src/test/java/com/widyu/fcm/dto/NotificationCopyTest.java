package com.widyu.fcm.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.widyu.fcm.NotificationType;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationCopyTest {
    @Test
    @DisplayName("문구 코드가 없으면 명시적인 예외가 발생한다")
    void 문구_코드가_없으면_명시적인_예외가_발생한다() {
        // given / when / then
        assertThatThrownBy(() -> NotificationCopy.of(NotificationType.SAFETY_SELF_CHECK, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("알림 문구 코드가 없습니다.");
        assertThatThrownBy(() -> NotificationCopy.of(NotificationType.SAFETY_SELF_CHECK, "  ", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("알림 문구 코드가 없습니다.");
    }

    @Test
    @DisplayName("본인확인 타입으로 심박 문구를 만들면 S01을 반환한다")
    void 본인확인_타입으로_심박_문구를_만들면_S01을_반환한다() {
        // given / when
        NotificationCopy heart = NotificationCopy.of(NotificationType.SAFETY_SELF_CHECK, "S01", Map.of());

        // then
        assertThat(heart.title()).isEqualTo("평소와 다른 심박이 감지됐어요.");
        assertThatThrownBy(() -> NotificationCopy.of(NotificationType.SAFETY_SELF_CHECK, "Z01", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("안심구역 Z01과 Z02 문구를 만들면 제목만 반환한다")
    void 안심구역_Z01과_Z02_문구를_만들면_제목만_반환한다() {
        // given / when
        Map<String, String> values = Map.of("시니어 이름", "어머니");
        NotificationCopy exit = NotificationCopy.of(NotificationType.SAFE_ZONE_EXITED, "Z01", values);
        NotificationCopy enter = NotificationCopy.of(NotificationType.SAFE_ZONE_ENTERED, "Z02", values);

        // then
        assertThat(exit.title()).isEqualTo("어머니 님이 안심구역을 벗어났어요.");
        assertThat(enter.title()).isEqualTo("어머니 님이 안심구역으로 돌아왔어요.");
        assertThat(exit.body()).isNull();
        assertThat(enter.body()).isNull();
    }

    @Test
    @DisplayName("보호자 이름 없이 문구를 만들면 가족으로 채운 완성 문장을 반환한다")
    void 보호자_이름_없이_문구를_만들면_가족으로_채운다() {
        // given / when
        NotificationCopy copy = NotificationCopy.of(NotificationType.MEDICATION_SCHEDULE_CHANGED, "M05", Map.of());

        // then
        assertThat(copy.title()).isEqualTo("가족 님이 약 알람을 변경했어요.");
        assertThat(copy.body()).isEqualTo("바뀐 약 알람은 내일부터 적용돼요.");
    }

    @Test
    @DisplayName("잠금화면 문구를 만들면 일정명과 메시지 원문을 노출하지 않는다")
    void 잠금화면_문구를_만들면_민감한_상세를_노출하지_않는다() {
        // given
        Map<String, String> values = Map.of("일정명", "민감한 진료", "메시지 미리보기", "개인 메시지 원문");

        // when
        NotificationCopy schedule = NotificationCopy.of(NotificationType.HEALTH_SCHEDULE_UPCOMING,
                "H01-S-OS", values);
        NotificationCopy message = NotificationCopy.of(NotificationType.HEART_MESSAGE_RECEIVED,
                "X01-OS", values);

        // then
        assertThat(schedule.title() + schedule.body()).doesNotContain("민감한 진료");
        assertThat(message.title() + message.body()).doesNotContain("개인 메시지 원문");
    }

    @Test
    @DisplayName("앱 내부 메시지 문구를 만들면 미리보기를 40자로 제한한다")
    void 앱_내부_메시지_문구를_만들면_미리보기를_제한한다() {
        // given
        String preview = "가".repeat(41);

        // when
        NotificationCopy copy = NotificationCopy.of(NotificationType.HEART_MESSAGE_RECEIVED,
                "X01-INAPP", Map.of("메시지 미리보기", preview));

        // then
        assertThat(copy.body()).hasSize(40);
    }

    @Test
    @DisplayName("다른 이벤트의 문구 코드를 선택하면 예외가 발생한다")
    void 다른_이벤트의_문구를_선택하면_예외가_발생한다() {
        // given / when / then
        assertThatThrownBy(() -> NotificationCopy.of(NotificationType.ALBUM_LIKED, "M05", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("남은 게시물 수를 모르면 숫자 없는 대체 문구를 반환한다")
    void 남은_게시물_수를_모르면_대체_문구를_반환한다() {
        // given / when
        NotificationCopy copy = NotificationCopy.of(NotificationType.ALBUM_UNLOCKED,
                "A06-FALLBACK", Map.of());

        // then
        assertThat(copy.title()).isEqualTo("게시물 잠금을 해제했어요.");
        assertThat(copy.body()).isEqualTo("앨범에서 확인해보세요.");
    }
}
