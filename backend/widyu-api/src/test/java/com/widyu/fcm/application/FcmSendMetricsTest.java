package com.widyu.fcm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FcmSendMetricsTest {

    @Test
    @DisplayName("같은 서비스 내부에서 호출해도 FCM 전송 시간을 기록한다")
    void 같은_서비스_내부에서_호출해도_FCM_전송_시간을_기록한다() {
        // given
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        FcmSendMetrics metrics = new FcmSendMetrics(meterRegistry);

        // when
        metrics.record(() -> { });

        // then
        assertThat(meterRegistry.find("fcm.send").timer())
                .isNotNull()
                .extracting(timer -> timer.count())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("FCM 전송이 예외를 던져도 시간을 기록한다")
    void FCM_전송이_예외를_던져도_시간을_기록한다() {
        // given
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        FcmSendMetrics metrics = new FcmSendMetrics(meterRegistry);

        // when & then
        assertThatThrownBy(() -> metrics.record(() -> {
            throw new IllegalStateException();
        })).isInstanceOf(IllegalStateException.class);
        assertThat(meterRegistry.find("fcm.send").timer())
                .isNotNull()
                .extracting(timer -> timer.count())
                .isEqualTo(1L);
    }
}
