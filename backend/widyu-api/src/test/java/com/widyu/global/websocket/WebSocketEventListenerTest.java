package com.widyu.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WebSocket 이벤트 리스너 테스트")
class WebSocketEventListenerTest {

    @Test
    @DisplayName("종료 사유에 줄바꿈이 있으면 이스케이프해 한 줄로 남긴다")
    void 종료_사유에_줄바꿈이_있으면_이스케이프해_한_줄로_남긴다() {
        // given
        String reason = "bye\r\n2026-10-13 INFO forged line";

        // when
        String escaped = WebSocketEventListener.escapeLineBreaks(reason);

        // then
        assertThat(escaped).isEqualTo("bye\\r\\n2026-10-13 INFO forged line");
        assertThat(escaped).doesNotContain("\r", "\n");
    }

    @Test
    @DisplayName("종료 사유가 없으면 null을 그대로 돌려준다")
    void 종료_사유가_없으면_null을_그대로_돌려준다() {
        // when
        String escaped = WebSocketEventListener.escapeLineBreaks(null);

        // then
        assertThat(escaped).isNull();
    }
}
