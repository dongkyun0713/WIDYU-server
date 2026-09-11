package com.widyu.global.log;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class ExceptionLogEntry {

    private String timestamp;
    private String exceptionType;
    private String message;
    private String requestUri;
    private String stackTrace;

    public String toLogString() {
        return String.format(
                "[EXCEPTION] Time=%s ExceptionType=%s Message=%s RequestURI=%s StackTrace=%s",
                timestamp,
                exceptionType,
                message,
                requestUri,
                escapeLineBreaks(stackTrace)
        );
    }

    private static String escapeLineBreaks(final String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\r", "\\r").replace("\n", "\\n");
    }
}
