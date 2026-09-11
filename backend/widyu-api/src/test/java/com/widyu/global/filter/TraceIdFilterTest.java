package com.widyu.global.filter;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("Trace ID 필터 단위 테스트")
class TraceIdFilterTest {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("유효한 요청 traceId를 응답 헤더와 MDC에 전달한다")
    void 유효한_요청_traceId를_응답_헤더와_MDC에_전달한다() throws ServletException, IOException {
        // given
        TraceIdFilter filter = new TraceIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TRACE_ID_HEADER, "frontend-request_1.2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = (servletRequest, servletResponse) ->
                assertThat(MDC.get("traceId")).isEqualTo("frontend-request_1.2");

        // when
        filter.doFilter(request, response, filterChain);

        // then
        assertThat(response.getHeader(TRACE_ID_HEADER)).isEqualTo("frontend-request_1.2");
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("유효하지 않은 요청 traceId에는 서버 UUID를 사용한다")
    void 유효하지_않은_요청_traceId에는_서버_UUID를_사용한다() throws ServletException, IOException {
        // given
        TraceIdFilter filter = new TraceIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TRACE_ID_HEADER, "invalid trace id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        // then
        assertThat(response.getHeader(TRACE_ID_HEADER)).matches("[a-f0-9-]{36}");
    }
}
