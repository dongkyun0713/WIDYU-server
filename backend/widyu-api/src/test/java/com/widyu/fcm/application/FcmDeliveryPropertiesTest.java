package com.widyu.fcm.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FcmDeliveryPropertiesTest {
    @Test
    @DisplayName("운영 정책 설정이 없으면 기동을 거부한다")
    void 정책_설정이_없으면_기동을_거부한다() {
        // given / when / then
        runner()
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("모든 필수 정책을 명시하면 일반과 긴급 TTL을 구분한다")
    void 필수_정책을_명시하면_TTL을_구분한다() {
        // given / when / then
        runner()
                .withPropertyValues("fcm.delivery.max-retries=5", "fcm.delivery.normal-ttl=24h",
                        "fcm.delivery.emergency-ttl=5m")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FcmDeliveryProperties properties = context.getBean(FcmDeliveryProperties.class);
                    assertThat(properties.ttl(false)).isEqualTo(java.time.Duration.ofHours(24));
                    assertThat(properties.ttl(true)).isEqualTo(java.time.Duration.ofMinutes(5));
                });
    }

    @Test
    @DisplayName("lease가 HTTP 제한보다 짧으면 기동을 거부한다")
    void 짧은_lease면_기동을_거부한다() {
        // given / when / then
        runner()
                .withPropertyValues("fcm.delivery.max-retries=5", "fcm.delivery.normal-ttl=24h",
                        "fcm.delivery.emergency-ttl=5m", "fcm.delivery.lease=5s")
                .run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory().setConversionService(
                        org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(FcmDeliveryProperties.class);
    }
}
