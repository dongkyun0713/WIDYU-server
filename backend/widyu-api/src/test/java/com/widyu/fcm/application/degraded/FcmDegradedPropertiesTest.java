package com.widyu.fcm.application.degraded;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FcmDegradedPropertiesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "fcm.degraded.snapshot-ttl=0s", "fcm.degraded.snapshot-ttl=-1s",
            "fcm.degraded.lease=0s", "fcm.degraded.lease=-1s",
            "fcm.degraded.stream-max-len=0", "fcm.degraded.stream-max-len=-1"})
    @DisplayName("저하 경로 설정에 0이나 음수를 주면 기동을 거부한다")
    void 저하경로_설정에_0이나_음수를_주면_기동을_거부한다(String property) {
        // given / when / then
        runner()
                .withPropertyValues(property)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("설정을 주지 않으면 켜진 상태로 스냅샷 24시간, lease 60초, 스트림 상한 10000을 쓴다")
    void 설정을_주지_않으면_기본값을_쓴다() {
        // given / when / then
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            FcmDegradedProperties properties = context.getBean(FcmDegradedProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.snapshotTtl()).isEqualTo(Duration.ofHours(24));
            assertThat(properties.lease()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.streamMaxLen()).isEqualTo(10000L);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev", "prod"})
    @DisplayName("배포 프로파일로 기동하면 저하 경로가 기본으로 켜진다")
    void 배포_프로파일은_저하경로가_기본으로_켜진다(String profile) {
        // given / when / then
        runner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=classpath:application-fcm.yml",
                        "spring.profiles.active=" + profile)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FcmDegradedProperties properties = context.getBean(FcmDegradedProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.snapshotTtl()).isEqualTo(Duration.ofHours(24));
                });
    }

    @Test
    @DisplayName("운영 환경변수로 끄면 저하 경로가 꺼진다")
    void 운영_환경변수로_끄면_저하경로가_꺼진다() {
        // given / when / then
        runner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=classpath:application-fcm.yml",
                        "spring.profiles.active=prod", "FCM_DEGRADED_ENABLED=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(FcmDegradedProperties.class).enabled()).isFalse();
                });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory().setConversionService(
                        ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(Config.class);
    }

    @EnableConfigurationProperties(FcmDegradedProperties.class)
    static class Config {
    }
}
