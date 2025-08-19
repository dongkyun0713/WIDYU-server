package com.widyu.global.error;

import com.widyu.global.log.BusinessExceptionLogEntry;
import com.widyu.global.log.ExceptionLogEntry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final String UNEXPECTED_SERVER_ERROR_MESSAGE = "예상치 못한 서버 에러입니다.";
    private static final String BUSINESS_LOG_MARKER = "BUSINESS-EXCEPTION-LOG";
    private static final String SYSTEM_LOG_MARKER = "EXCEPTION-LOG";

    @ExceptionHandler(BusinessException.class)
    public ErrorResponse handleBusinessException(
            final BusinessException ex,
            final HttpServletRequest request
    ) {
        doBusinessLog(ex, request);

        final ErrorCode errorCode = ex.getErrorCode();
        final String detail = nullSafe(ex.getMessage(), errorCode.getMessage());

        return ErrorResponse.of(
                errorCode.getCode(),
                detail
        );
    }

    @ExceptionHandler(Exception.class)
    public ErrorResponse handleException(
            final Exception ex,
            final HttpServletRequest request
    ) {
        doSystemLog(ex, request);

        return ErrorResponse.of(
                ErrorCode.INTERNAL_SERVER_ERROR.getCode(),
                UNEXPECTED_SERVER_ERROR_MESSAGE
        );
    }

    private void doBusinessLog(final RuntimeException runtimeException, final HttpServletRequest request) {
        final BusinessExceptionLogEntry entry = BusinessExceptionLogEntry.builder()
                .timestamp(OffsetDateTime.now().toString())
                .exceptionType(runtimeException.getClass().getName())
                .message(runtimeException.getMessage())
                .requestUri(request.getRequestURI())
                .build();
        final Marker marker = MarkerFactory.getMarker(BUSINESS_LOG_MARKER);
        log.warn(marker, entry.toLogString());
    }

    private void doSystemLog(final Exception exception, final HttpServletRequest request) {
        final ExceptionLogEntry entry = ExceptionLogEntry.builder()
                .timestamp(OffsetDateTime.now().toString())
                .exceptionType(exception.getClass().getName())
                .message(exception.getMessage())
                .requestUri(request.getRequestURI())
                .stackTrace(toStackTraceLog(exception))
                .build();
        final Marker marker = MarkerFactory.getMarker(SYSTEM_LOG_MARKER);
        log.error(marker, entry.toLogString());
    }

    private static String nullSafe(final String primary, final String fallback) {
        return (primary == null || primary.isBlank()) ? fallback : primary;
    }

    private String toStackTraceLog(final Exception exception) {
        return Arrays.stream(exception.getStackTrace())
                .map(StackTraceElement::toString)
                .collect(Collectors.joining("\n"));
    }
}
