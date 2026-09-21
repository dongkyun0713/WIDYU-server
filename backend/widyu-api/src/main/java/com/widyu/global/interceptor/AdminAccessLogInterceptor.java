package com.widyu.global.interceptor;

import com.widyu.admin.application.AdminAccessLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 관리자 API 요청을 접속기록으로 남긴다(LLD-0057 5절). 요청 본문과 헤더 값, 쿼리 값은 읽지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAccessLogInterceptor implements HandlerInterceptor {

    public static final String START_TIME_ATTRIBUTE = AdminAccessLogInterceptor.class.getName() + ".startedAt";
    private static final String[] TARGET_REF_VARIABLES = {"runId", "participationId", "exportId"};

    private final AdminAccessLogService adminAccessLogService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME_ATTRIBUTE, LocalDateTime.now());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception exception) {
        try {
            Map<String, String> pathVariables = pathVariables(request);
            adminAccessLogService.record(
                    request.getMethod(),
                    request.getRequestURI(),
                    queryParameterNames(request),
                    targetMemberId(pathVariables),
                    targetRef(pathVariables),
                    response.getStatus(),
                    clientIp(request),
                    request.getHeader("User-Agent"),
                    startedAt(request));
        } catch (Exception failure) {
            // 기록 실패가 응답에 영향을 주면 안 된다. 요청 값이 섞이지 않게 예외 클래스명과 경로만 남긴다.
            log.warn("관리자 접속기록 저장 실패 type={} path={}", failure.getClass().getSimpleName(),
                    request.getRequestURI());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> pathVariables(HttpServletRequest request) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (attribute instanceof Map<?, ?> variables) {
            return (Map<String, String>) variables;
        }
        return Map.of();
    }

    private Long targetMemberId(Map<String, String> pathVariables) {
        String value = pathVariables.get("memberId");
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String targetRef(Map<String, String> pathVariables) {
        for (String name : TARGET_REF_VARIABLES) {
            String value = pathVariables.get(name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return request.getRemoteAddr();
        }
        return forwardedFor.split(",")[0].trim();
    }

    /**
     * 검색어·이름 같은 개인정보가 접속기록에 복제되지 않도록 쿼리 값은 버리고 키 이름만 남긴다.
     * {@code getParameterMap()}은 폼 본문까지 파싱할 수 있어 사용하지 않고 URL 쿼리 문자열만 다룬다.
     */
    private String queryParameterNames(HttpServletRequest request) {
        String query = request.getQueryString();
        if (query == null || query.isBlank()) {
            return null;
        }

        Set<String> names = Arrays.stream(query.split("&"))
                .map(this::parameterName)
                .filter(name -> !name.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (names.isEmpty()) {
            return null;
        }
        return String.join("&", names);
    }

    private String parameterName(String pair) {
        int separator = pair.indexOf('=');
        if (separator < 0) {
            return pair;
        }
        return pair.substring(0, separator);
    }

    private LocalDateTime startedAt(HttpServletRequest request) {
        if (request.getAttribute(START_TIME_ATTRIBUTE) instanceof LocalDateTime startedAt) {
            return startedAt;
        }
        return LocalDateTime.now();
    }
}
