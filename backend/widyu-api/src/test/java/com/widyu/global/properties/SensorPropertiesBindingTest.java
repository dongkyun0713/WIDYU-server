package com.widyu.global.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.MapPropertySource;
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
        assertThat(properties.incident().selfCheckSec()).isEqualTo(60L);
        assertThat(properties.incident().timeoutPollMs()).isEqualTo(5000L);
        assertThat(properties.incident().selfCheckFirst()).isFalse();
        assertThat(properties.incident().situationWindowMin()).isEqualTo(5);
        assertThat(properties.medication().onTimePush()).isTrue();
    }

    @Test
    @DisplayName("기본 센서 설정을 바인딩하면 후속 카드 기능을 끈다")
    void 기본_설정을_바인딩하면_후속_카드를_끈다() throws IOException {
        // given
        Binder binder = binderOf("application-sensor.yml");

        // when
        SensorProperties properties = binder.bind("sensor", SensorProperties.class).get();

        // then
        assertThat(properties.followup().enabled()).isFalse();
        assertThat(properties.followup().rewardEnabled()).isFalse();
    }

    @Test
    @DisplayName("후속 보상 설정을 켜면 rewardEnabled에 참을 바인딩한다")
    void 후속_보상_설정을_켜면_참을_바인딩한다() throws IOException {
        // given
        Binder binder = binderOf("application-sensor.yml",
                Map.of("SENSOR_FOLLOWUP_REWARD_ENABLED", "true"));

        // when
        SensorProperties properties = binder.bind("sensor", SensorProperties.class).get();

        // then
        assertThat(properties.followup().enabled()).isFalse();
        assertThat(properties.followup().rewardEnabled()).isTrue();
    }

    @Test
    @DisplayName("정각 서버 푸시 설정을 끄면 거짓을 바인딩한다")
    void 정각_서버_푸시_설정을_끄면_거짓을_바인딩한다() throws IOException {
        // given
        Binder binder = binderOf("application-sensor.yml",
                Map.of("SENSOR_MEDICATION_ON_TIME_PUSH", "false"));

        // when
        SensorProperties properties = binder.bind("sensor", SensorProperties.class).get();

        // then
        assertThat(properties.medication().onTimePush()).isFalse();
    }

    private Binder binderOf(String classpathLocation) throws IOException {
        return binderOf(classpathLocation, Map.of());
    }

    private Binder binderOf(String classpathLocation, Map<String, Object> overrides) throws IOException {
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load(classpathLocation, new ClassPathResource(classpathLocation));
        MutablePropertySources sources = new MutablePropertySources();
        loaded.forEach(sources::addLast);
        sources.addFirst(new MapPropertySource("test-overrides", overrides));
        return new Binder(
                ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
    }
}
