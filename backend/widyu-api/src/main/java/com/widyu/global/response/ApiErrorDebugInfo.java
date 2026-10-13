package com.widyu.global.response;

import org.springframework.core.NestedExceptionUtils;

/**
 * dev·local의 5xx 응답에만 붙는 진단 정보(LLD-0027, LLD-0085).
 * message는 가장 안쪽 원인의 메시지다. 프론트 개발자가 Grafana 없이 원인을 볼 수 있게 한다. prod에는 붙지 않는다.
 */
public record ApiErrorDebugInfo(
        String exceptionType,
        String requestUri,
        String message
) {

    private static final int MESSAGE_LIMIT = 300;

    public static ApiErrorDebugInfo of(final Exception exception, final String requestUri) {
        return new ApiErrorDebugInfo(exception.getClass().getSimpleName(), requestUri, messageOf(exception));
    }

    private static String messageOf(final Exception exception) {
        final String message = NestedExceptionUtils.getMostSpecificCause(exception).getMessage();
        if (message == null) {
            return null;
        }
        final String singleLine = message.replace("\r", " ").replace("\n", " ").strip();
        if (singleLine.length() <= MESSAGE_LIMIT) {
            return singleLine;
        }
        return singleLine.substring(0, MESSAGE_LIMIT) + "…";
    }
}
