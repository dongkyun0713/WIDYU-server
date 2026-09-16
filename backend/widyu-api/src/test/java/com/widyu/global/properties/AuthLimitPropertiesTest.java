package com.widyu.global.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AuthLimitPropertiesTest {

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev", "test"})
    @DisplayName("비운영 프로파일로 기동하면 문자 전체 예산이 채워져 발송을 거부하지 않는다")
    void 비운영_프로파일은_문자_전체_예산을_갖는다(String profile) {
        // given / when
        runner().withPropertyValues("spring.profiles.active=" + profile)
                // then
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AuthLimitProperties.class).smsGlobalDay()).isPositive();
                });
    }

    @Test
    @DisplayName("운영 프로파일은 문자 전체 예산을 문서에서 받지 않아 환경변수 입력을 강제한다")
    void 운영_프로파일은_문자_전체_예산을_문서에서_받지_않는다() {
        // given / when
        runner().withPropertyValues("spring.profiles.active=prod")
                // then
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AuthLimitProperties.class).smsGlobalDay()).isNull();
                });
    }

    @Test
    @DisplayName("환경변수로 전체 예산을 주면 문서 기본값 대신 그 값을 적용한다")
    void 환경변수가_문서_기본값을_대체한다() {
        // given / when
        runner().withPropertyValues("spring.profiles.active=dev", "AUTH_LIMITS_SMSGLOBALDAY=7")
                // then
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AuthLimitProperties.class).smsGlobalDay()).isEqualTo(7);
                });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory().setConversionService(
                        ApplicationConversionService.getSharedInstance()))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=classpath:application-auth.yml")
                .withUserConfiguration(AuthLimitConfig.class);
    }

    @EnableConfigurationProperties(AuthLimitProperties.class)
    static class AuthLimitConfig {
    }
}
