package com.widyu.global.log;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExceptionLogEntryTest {

    @Test
    @DisplayName("예외 로그를 생성하면 상세 정보가 한 줄로 기록된다")
    void 예외_로그를_생성하면_상세_정보가_한_줄로_기록된다() {
        ExceptionLogEntry entry = ExceptionLogEntry.builder()
                .timestamp("2026-09-12T03:43:28Z")
                .exceptionType("java.lang.IllegalStateException")
                .message("처리\n실패")
                .requestUri("/api/v1/test")
                .stackTrace("line one\nline two")
                .build();

        String log = entry.toLogString();

        assertThat(log)
                .contains("ExceptionType=java.lang.IllegalStateException")
                .contains("Message=처리\\n실패")
                .contains("StackTrace=line one\\nline two")
                .doesNotContain("\n");
    }
}
