package com.widyu.global.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 센서 설정 파일의 들여쓰기가 틀리면 {@link SensorProperties.Export}가 조용히 null로 바인딩된다.
 * 프로퍼티를 직접 만들어 넘기는 단위 테스트로는 잡히지 않으므로 실제 YAML을 읽어 검증한다.
 */
class SensorPropertiesBindingTest {

    @Test
    @DisplayName("실제 센서 설정 파일을 바인딩하면 회차 내보내기 상수가 export 아래에서 채워진다")
    void 실제_설정_파일을_바인딩하면_내보내기_상수가_채워진다() throws IOException {
        // given
        Binder binder = binderOf("application-sensor.yml");

        // when
        SensorProperties properties = binder.bind("sensor", SensorProperties.class).get();

        // then
        SensorProperties.Export export = properties.export();
        assertThat(export.serverBuild()).isEqualTo("unknown");
        assertThat(export.expectedPeriodMs()).containsEntry("hr", 1000L);
        assertThat(export.gapThresholdMs()).containsEntry("hr", 5000L);
        assertThat(export.clock()).isNotNull();
        assertThat(export.clock().sourceDomain()).isEqualTo("DEVICE_MONOTONIC");
        assertThat(properties.fallAi().enabled()).isFalse();
        assertThat(properties.incident().selfCheckSec()).isEqualTo(45L);
        assertThat(properties.incident().timeoutPollMs()).isEqualTo(5000L);
    }

    private Binder binderOf(String classpathLocation) throws IOException {
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load(classpathLocation, new ClassPathResource(classpathLocation));
        MutablePropertySources sources = new MutablePropertySources();
        loaded.forEach(sources::addLast);
        return new Binder(
                ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
    }
}
