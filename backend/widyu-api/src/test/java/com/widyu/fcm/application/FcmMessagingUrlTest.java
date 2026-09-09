package com.widyu.fcm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FcmMessagingUrlTest {

    @Test
    @DisplayName("공식 HTTPS FCM URL이면 사용한다")
    void 공식_HTTPS_FCM_URL이면_사용한다() {
        // when
        FcmMessagingUrl messagingUrl = new FcmMessagingUrl(
                "https://fcm.googleapis.com/v1/projects/widyu-d384f/messages:send");

        // then
        assertThat(messagingUrl.value().getHost()).isEqualTo("fcm.googleapis.com");
    }

    @Test
    @DisplayName("HTTP 또는 외부 호스트 URL이면 애플리케이션 시작을 거부한다")
    void HTTP_또는_외부_호스트_URL이면_애플리케이션_시작을_거부한다() {
        // when & then
        assertThatThrownBy(() -> new FcmMessagingUrl("http://fcm.googleapis.com/v1/projects/widyu/messages:send"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FcmMessagingUrl("https://attacker.example/messages"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
