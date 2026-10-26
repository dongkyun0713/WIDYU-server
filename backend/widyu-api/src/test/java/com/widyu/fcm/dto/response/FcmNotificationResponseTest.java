package com.widyu.fcm.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.FcmNotification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FcmNotificationResponseTest {

    @Test
    @DisplayName("알림 목록 응답은 기존 카테고리와 경로 및 읽음 상태를 반환한다")
    void 알림_목록은_카테고리_경로와_읽음_상태를_반환한다() {
        // given
        FcmNotification notification = FcmNotification.builder()
                .id(1465L)
                .fcmCategory(FcmCategory.ALBUM)
                .image("https://cdn.widyu.store/profile/123.png")
                .deepLink("/album")
                .isRead(false)
                .build();
        ObjectMapper mapper = new ObjectMapper();

        // when
        FcmNotificationResponse unread = FcmNotificationResponse.from(notification);
        JsonNode unreadJson = mapper.valueToTree(unread);
        notification.markAsRead();
        FcmNotificationResponse read = FcmNotificationResponse.from(notification);
        JsonNode readJson = mapper.valueToTree(read);

        // then
        assertThat(unreadJson.path("category").asText()).isEqualTo("ALBUM");
        assertThat(unreadJson.path("image").asText())
                .isEqualTo("https://cdn.widyu.store/profile/123.png");
        assertThat(unreadJson.path("path").asText()).isEqualTo("/album");
        assertThat(unreadJson.path("isRead").asBoolean()).isFalse();
        assertThat(unreadJson.has("scheme")).isFalse();
        assertThat(readJson.path("isRead").asBoolean()).isTrue();
    }
}
