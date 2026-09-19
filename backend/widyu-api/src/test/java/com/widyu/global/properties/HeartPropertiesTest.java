package com.widyu.global.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

@DisplayName("HeartProperties 바인딩 단위 테스트")
class HeartPropertiesTest {

    private HeartProperties bind(String exemptMemberIds) {
        MapConfigurationPropertySource source =
                new MapConfigurationPropertySource(Map.of("heart.cleanup.exempt-member-ids", exemptMemberIds));
        return new Binder(source).bind("heart", Bindable.of(HeartProperties.class)).get();
    }

    @Test
    @DisplayName("쉼표로 구분한 회원 ID를 바인딩하면 순서대로 목록이 된다")
    void 쉼표로_구분한_회원_ID를_바인딩하면_순서대로_목록이_된다() {
        // given
        String exemptMemberIds = "1023,1077";

        // when
        HeartProperties properties = bind(exemptMemberIds);

        // then
        assertThat(properties.cleanup().exemptMemberIds()).containsExactly(1023L, 1077L);
    }

    @Test
    @DisplayName("빈 문자열을 바인딩하면 제외 목록이 비어 있다")
    void 빈_문자열을_바인딩하면_제외_목록이_비어_있다() {
        // given
        String exemptMemberIds = "";

        // when
        HeartProperties properties = bind(exemptMemberIds);

        // then
        assertThat(properties.cleanup().exemptMemberIds()).isEmpty();
    }
}
