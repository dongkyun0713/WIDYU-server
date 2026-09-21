package com.widyu.global.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import com.widyu.admin.application.AdminAccessLogService;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

@DisplayName("관리자 접속기록 인터셉터 단위 테스트")
@ExtendWith(MockitoExtension.class)
class AdminAccessLogInterceptorTest {

    @Mock
    private AdminAccessLogService adminAccessLogService;

    @InjectMocks
    private AdminAccessLogInterceptor interceptor;

    @Test
    @DisplayName("회원 상세를 조회하면 대상 회원과 상태 코드를 담아 접속기록을 남긴다")
    void 회원_상세를_조회하면_대상_회원과_상태_코드를_기록한다() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/members/42");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("memberId", "42"));
        request.setQueryString("detail=true&name=%EA%B9%80%EB%8F%99%EA%B7%A0");
        request.setRemoteAddr("10.0.0.7");
        request.addHeader("User-Agent", "widyu-admin/1.0");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        // when
        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        // then
        ArgumentCaptor<LocalDateTime> accessedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        then(adminAccessLogService).should().record(
                eq("GET"),
                eq("/api/v1/admin/members/42"),
                eq("detail&name"),
                eq(42L),
                isNull(),
                eq(200),
                eq("10.0.0.7"),
                eq("widyu-admin/1.0"),
                accessedAt.capture());
        assertThat(accessedAt.getValue()).isEqualTo(request.getAttribute(AdminAccessLogInterceptor.START_TIME_ATTRIBUTE));
    }

    @Test
    @DisplayName("관리자 검색어가 있어도 쿼리 값은 버리고 파라미터 이름만 기록한다")
    void 관리자_검색어는_값을_버리고_파라미터_이름만_기록한다() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/search");
        request.setQueryString("q=010-1234-5678&page=0&q=senior-name");
        request.setRemoteAddr("10.0.0.8");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        interceptor.afterCompletion(request, response, new Object(), null);

        // then
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        then(adminAccessLogService).should().record(
                anyString(), anyString(), query.capture(), any(), any(), anyInt(),
                anyString(), any(), any(LocalDateTime.class));
        assertThat(query.getValue()).isEqualTo("q&page");
        assertThat(query.getValue()).doesNotContain("010-1234-5678", "senior-name");
    }

    @Test
    @DisplayName("인증 전 관리자 로그인 요청도 접속기록을 남긴다")
    void 인증_전_관리자_로그인_요청도_기록한다() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/admin/login");
        request.setRemoteAddr("10.0.0.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        // when
        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        // then
        then(adminAccessLogService).should().record(
                eq("POST"),
                eq("/api/v1/auth/admin/login"),
                isNull(),
                isNull(),
                isNull(),
                eq(200),
                eq("10.0.0.9"),
                isNull(),
                any(LocalDateTime.class));
    }

    @Test
    @DisplayName("접속기록 저장이 예외를 던져도 요청 처리에는 예외가 전파되지 않는다")
    void 접속기록_저장이_실패해도_예외가_전파되지_않는다() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/members");
        MockHttpServletResponse response = new MockHttpServletResponse();
        willThrow(new IllegalStateException("DB down")).given(adminAccessLogService).record(
                anyString(), anyString(), any(), any(), any(), anyInt(), any(), any(), any());

        // when & then
        assertThatCode(() -> interceptor.afterCompletion(request, response, new Object(), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("요청 본문과 인증 헤더가 있어도 접속기록 인자에 담기지 않는다")
    void 요청_본문과_인증_헤더는_기록하지_않는다() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/admin/login");
        request.setContent("{\"email\":\"admin@widyu.com\",\"password\":\"secret\"}".getBytes());
        request.addHeader("Authorization", "Bearer super-secret-token");
        request.setRemoteAddr("10.0.0.3");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        interceptor.afterCompletion(request, response, new Object(), null);

        // then
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        then(adminAccessLogService).should().record(
                anyString(), anyString(), query.capture(), any(), any(), anyInt(),
                anyString(), any(), any(LocalDateTime.class));
        assertThat(query.getValue()).isNull();
    }

    @Test
    @DisplayName("측정회차 경로 변수가 있으면 대상 참조로 기록한다")
    void 측정회차_경로_변수는_대상_참조로_기록한다() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/collection-runs/run-1");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("runId", "run-1"));
        request.setRemoteAddr("10.0.0.4");
        request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        interceptor.afterCompletion(request, response, new Object(), null);

        // then
        then(adminAccessLogService).should().record(
                anyString(), anyString(), any(),
                isNull(),
                eq("run-1"),
                anyInt(),
                eq("203.0.113.5"),
                any(), any(LocalDateTime.class));
    }
}
