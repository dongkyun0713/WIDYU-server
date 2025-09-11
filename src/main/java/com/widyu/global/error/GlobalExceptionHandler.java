package com.widyu.global.error;

import com.widyu.global.log.BusinessExceptionLogEntry;
import com.widyu.global.log.ExceptionLogEntry;
import com.widyu.global.response.ApiResponseTemplate;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final String BUSINESS_LOG_MARKER = "BUSINESS-EXCEPTION-LOG";
    private static final String SYSTEM_LOG_MARKER = "EXCEPTION-LOG";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponseTemplate<Void>> handleBusinessException(
            final BusinessException ex,
            final HttpServletRequest request
    ) {
        doBusinessLog(ex, request);

        final ErrorCode errorCode = ex.getErrorCode();
        final String detail = nullSafe(ex.getMessage(), errorCode.getMessage());

        final HttpStatus status = getHttpStatusOrDefault(errorCode);

        return toResponse(status, errorCode.getCode(), detail);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    protected ResponseEntity<ApiResponseTemplate<Void>> handleMethodArgumentNotValidException(
            final MethodArgumentNotValidException e
    ) {
        final FieldError fieldError = e.getBindingResult().getFieldError();

        final String field = (fieldError != null) ? fieldError.getField() : "unknown";
        final String defaultMsg = (fieldError != null && fieldError.getDefaultMessage() != null)
                ? fieldError.getDefaultMessage()
                : "요청 값이 유효하지 않습니다";

        final String message = String.format("%s (%s)", trimTrailingPeriod(defaultMsg), field);

        log.error("Validation error for field {}: {}", field, defaultMsg);

        return toResponse(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST.getCode(), message);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponseTemplate<Void>> handleException(
            final Exception ex,
            final HttpServletRequest request
    ) {
        doSystemLog(ex, request);

        return toResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_SERVER_ERROR.getCode(),
                ErrorCode.INTERNAL_SERVER_ERROR.getMessage()
        );
    }

    private ResponseEntity<ApiResponseTemplate<Void>> toResponse(
            final HttpStatus status,
            final String code,
            final String message
    ) {
        final ApiResponseTemplate<Void> body = ApiResponseTemplate.<Void>error()
                .code(code)
                .message(message)
                .build(); // data = null

        return ResponseEntity.status(status).body(body);
    }

    private static HttpStatus getHttpStatusOrDefault(final ErrorCode errorCode) {
        try {
            final HttpStatus s = errorCode.getHttpStatus();
            return (s != null) ? s : HttpStatus.BAD_REQUEST;
        } catch (Exception ignore) {
            return HttpStatus.BAD_REQUEST;
        }
    }

    private static String trimTrailingPeriod(final String s) {
        if (s == null) {
            return null;
        }
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '.') {
            end--;
        }
        return (end == s.length()) ? s : s.substring(0, end);
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
