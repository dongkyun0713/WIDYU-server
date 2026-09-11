package com.widyu.global.response;

public record ApiErrorDebugInfo(
        String exceptionType,
        String requestUri
) {

    public static ApiErrorDebugInfo of(final Exception exception, final String requestUri) {
        return new ApiErrorDebugInfo(exception.getClass().getSimpleName(), requestUri);
    }
}
