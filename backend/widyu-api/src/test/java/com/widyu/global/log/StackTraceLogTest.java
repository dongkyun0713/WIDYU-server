package com.widyu.global.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StackTraceLogTest {

    @Test
    @DisplayName("원인 예외가 있으면 원인 종류와 프레임까지 한 줄로 남긴다")
    void 원인_예외가_있으면_원인_종류와_프레임까지_한_줄로_남긴다() {
        // given
        SQLException cause = new SQLException("connection refused");
        cause.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.mysql.Driver", "connect", "Driver.java", 10)
        });
        IllegalStateException exception = new IllegalStateException("조회 실패", cause);
        exception.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.widyu.pay.application.PaymentService", "confirm", "PaymentService.java", 42)
        });

        // when
        String line = StackTraceLog.oneLine(exception);

        // then
        assertThat(line).isEqualTo("com.widyu.pay.application.PaymentService.confirm(PaymentService.java:42)\\n"
                + "Caused by: java.sql.SQLException: connection refused\\n"
                + "com.mysql.Driver.connect(Driver.java:10)");
    }

    @Test
    @DisplayName("원인이 순환해도 같은 예외를 다시 적지 않는다")
    void 원인이_순환해도_같은_예외를_다시_적지_않는다() {
        // given
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);
        first.setStackTrace(new StackTraceElement[0]);
        second.setStackTrace(new StackTraceElement[0]);

        // when
        String line = StackTraceLog.oneLine(first);

        // then
        assertThat(line).isEqualTo("Caused by: java.lang.RuntimeException: second");
    }
}
