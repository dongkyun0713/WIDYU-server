package com.widyu.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("API 응답 템플릿 단위 테스트")
class ApiResponseTemplateTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("성공 응답에도 MDC traceId를 포함한다")
    void 성공_응답에도_MDC_traceId를_포함한다() {
        // given
        MDC.put("traceId", "frontend-request-1");

        // when
        ApiResponseTemplate<String> response = ApiResponseTemplate.ok()
                .code("SUCCESS")
                .message("성공")
                .body("data");

        // then
        assertThat(response.getTraceId()).isEqualTo("frontend-request-1");
    }
}
