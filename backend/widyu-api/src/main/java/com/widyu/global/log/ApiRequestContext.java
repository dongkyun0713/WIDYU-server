package com.widyu.global.log;

import com.widyu.global.security.PrincipalDetails;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 에러 알림이 "어떤 기능에서 누가 누구에게 하려다 실패했는지"를 보여 주도록 요청 맥락을 꺼낸다(LLD-0084).
 * 알림은 외부 서비스(Discord)로 나가므로 내부 회원 ID와 ID 경로 변수만 남기고, 결제 키처럼 ID가 아닌 경로 값은 뺀다.
 */
public final class ApiRequestContext {

    static final String UNKNOWN_API = "알 수 없는 API";
    static final String ANONYMOUS = "anonymous";
    static final String NONE = "-";
    private static final String ROLE_PREFIX = "ROLE_";

    private ApiRequestContext() {
    }

    /** Swagger summary가 기능 이름이다. 없으면 컨트롤러.메서드, 핸들러 매핑 전 예외면 알 수 없는 API. */
    public static String apiName(final HttpServletRequest request) {
        if (!(request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE) instanceof HandlerMethod handler)) {
            return UNKNOWN_API;
        }
        final Operation operation = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), Operation.class);
        if (operation != null && !operation.summary().isBlank()) {
            return singleLine(operation.summary());
        }
        return handler.getBeanType().getSimpleName() + "." + handler.getMethod().getName();
    }

    /**
     * 실제 URI 대신 매핑 패턴을 써서 ID가 다른 같은 API를 한 알림으로 묶는다.
     * 패턴이 없으면(핸들러 매핑 전 실패) 실제 URI를 쓰지 않는다. 알림 레이블과 AI 입력에 ID가 섞이기 때문이다.
     */
    public static String pathPattern(final HttpServletRequest request) {
        if (request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String pattern) {
            return pattern;
        }
        return NONE;
    }

    /** 요청자. 예: USER#12, 비로그인은 anonymous. */
    public static String actor() {
        return actorOf(SecurityContextHolder.getContext().getAuthentication());
    }

    /** WebSocket처럼 SecurityContext 대신 메시지 헤더에 인증 정보가 있는 경로에서 쓴다. */
    public static String actorOf(final Object user) {
        if (!(user instanceof Authentication authentication)
                || !(authentication.getPrincipal() instanceof PrincipalDetails principal)) {
            return ANONYMOUS;
        }
        final String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.replace(ROLE_PREFIX, ""))
                .findFirst()
                .orElse("MEMBER");
        return role + "#" + principal.getMemberId();
    }

    /** 요청 대상. ID 경로 변수만 `seniorId=34,albumId=7`처럼 남긴다. */
    public static String target(final HttpServletRequest request) {
        if (!(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables)) {
            return NONE;
        }
        final String target = variables.entrySet().stream()
                .filter(entry -> isIdName(String.valueOf(entry.getKey())))
                .map(entry -> entry.getKey() + "=" + singleLine(String.valueOf(entry.getValue())).replace(' ', '_'))
                .sorted()
                .collect(Collectors.joining(","));
        if (target.isEmpty()) {
            return NONE;
        }
        return target;
    }

    private static boolean isIdName(final String name) {
        return name.equals("id") || name.endsWith("Id");
    }

    private static String singleLine(final String value) {
        return value.replace("\r", " ").replace("\n", " ").strip();
    }
}
