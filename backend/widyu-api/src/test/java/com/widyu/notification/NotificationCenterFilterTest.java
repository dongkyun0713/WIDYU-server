package com.widyu.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationCenterFilterTest {
    @Test
    @DisplayName("카테고리 필터의 타입을 조회하면 빈 목록을 반환하지 않는다")
    void 카테고리_필터의_타입을_조회하면_빈_목록을_반환하지_않는다() {
        // given / when / then
        for (NotificationCenterFilter filter : NotificationCenterFilter.values()) {
            if (filter.isCategory()) {
                assertThat(filter.types()).as(filter.name()).isNotEmpty();
            }
        }
    }
}
