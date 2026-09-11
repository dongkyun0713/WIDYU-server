package com.widyu.global.error;

import com.widyu.global.log.BusinessExceptionLogEntry;
import com.widyu.global.log.ExceptionLogEntry;
import com.widyu.global.response.ApiErrorDebugInfo;
import com.widyu.global.response.ApiResponseTemplate;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final String BUSINESS_LOG_MARKER = "BUSINESS-EXCEPTION-LOG";
    private static final String SYSTEM_LOG_MARKER = "EXCEPTION-LOG";

    @Value("${observability.error-response.include-debug-details:false}")
    private boolean includeDebugDetails;

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponseTemplate<Void>> handleBusinessException(
            final BusinessException ex,
            final HttpServletRequest request
    ) {
        doBusinessLog(ex, request);

        final ErrorCode errorCode = ex.getErrorCode();
        final String detail = nullSafe(ex.getMessage(), errorCode.getMessage());

        final HttpStatus status = getHttpStatusOrDefault(errorCode);

        return toResponse(status, errorCode.getCode(), detail, runtimeDebugInfo(ex, request, status));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    protected ResponseEntity<ApiResponseTemplate<Void>> handleMethodArgumentNotValidException(
            final MethodArgumentNotValidException e
    ) {
        final FieldError fieldError = e.getBindingResult().getFieldError();

        String field = "unknown";
        String defaultMsg = "요청 값이 유효하지 않습니다";
        if (fieldError != null) {
            field = fieldError.getField();
            if (fieldError.getDefaultMessage() != null) {
                defaultMsg = fieldError.getDefaultMessage();
            }
        }

        final String message = String.format("%s (%s)", trimTrailingPeriod(defaultMsg), field);

        log.error("Validation error for field {}: {}", field, defaultMsg);

        return toResponse(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST.getCode(), message);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    protected ResponseEntity<ApiResponseTemplate<Void>> handleHandlerMethodValidationException(
            final HandlerMethodValidationException e
    ) {
        String message = e.getAllValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .map(error -> error.getDefaultMessage())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("요청 값이 유효하지 않습니다");

        log.error("Validation error: {}", message);

        return toResponse(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST.getCode(), message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    protected ResponseEntity<ApiResponseTemplate<Void>> handleHttpMessageNotReadableException(
            final HttpMessageNotReadableException e
    ) {
        log.warn("Malformed request body: {}", e.getMessage());

        return toResponse(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST.getCode(), "요청 본문을 읽을 수 없습니다. 형식을 확인해주세요.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    protected ResponseEntity<ApiResponseTemplate<Void>> handleMethodArgumentTypeMismatchException(
            final MethodArgumentTypeMismatchException e
    ) {
        final String message = String.format("요청 값의 타입이 올바르지 않습니다 (%s)", e.getName());
        log.warn("Type mismatch for parameter {}: {}", e.getName(), e.getMessage());

        return toResponse(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST.getCode(), message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    protected ResponseEntity<ApiResponseTemplate<Void>> handleMissingServletRequestParameterException(
            final MissingServletRequestParameterException e
    ) {
        final String message = String.format("필수 요청 파라미터가 누락되었습니다 (%s)", e.getParameterName());
        log.warn("Missing request parameter: {}", e.getParameterName());

        return toResponse(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST.getCode(), message);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    protected ResponseEntity<ApiResponseTemplate<Void>> handleMissingServletRequestPartException(
            final MissingServletRequestPartException e
    ) {
        final String message = String.format("필수 요청 파트가 누락되었습니다 (%s)", e.getRequestPartName());
        log.warn("Missing request part: {}", e.getRequestPartName());

        return toResponse(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST.getCode(), message);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    protected ResponseEntity<ApiResponseTemplate<Void>> handleMaxUploadSizeExceededException(
            final MaxUploadSizeExceededException e
    ) {
        log.warn("Upload size exceeded: {}", e.getMessage());

        return toResponse(
                ErrorCode.PAYLOAD_TOO_LARGE.getHttpStatus(),
                ErrorCode.PAYLOAD_TOO_LARGE.getCode(),
                ErrorCode.PAYLOAD_TOO_LARGE.getMessage()
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    protected ResponseEntity<ApiResponseTemplate<Void>> handleHttpRequestMethodNotSupportedException(
            final HttpRequestMethodNotSupportedException e
    ) {
        final String message = String.format("지원하지 않는 HTTP 메서드입니다 (%s)", e.getMethod());
        log.warn("Method not supported: {}", e.getMethod());

        return toResponse(ErrorCode.METHOD_NOT_ALLOWED.getHttpStatus(), ErrorCode.METHOD_NOT_ALLOWED.getCode(), message);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    protected ResponseEntity<ApiResponseTemplate<Void>> handleHttpMediaTypeNotSupportedException(
            final HttpMediaTypeNotSupportedException e
    ) {
        log.warn("Media type not supported: {}", e.getContentType());

        return toResponse(
                ErrorCode.UNSUPPORTED_MEDIA_TYPE.getHttpStatus(),
                ErrorCode.UNSUPPORTED_MEDIA_TYPE.getCode(),
                ErrorCode.UNSUPPORTED_MEDIA_TYPE.getMessage()
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    protected ResponseEntity<ApiResponseTemplate<Void>> handleNoResourceFoundException(
            final NoResourceFoundException e
    ) {
        log.warn("No resource found: {}", e.getResourcePath());

        return toResponse(
                ErrorCode.NOT_FOUND.getHttpStatus(),
                ErrorCode.NOT_FOUND.getCode(),
                ErrorCode.NOT_FOUND.getMessage()
        );
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponseTemplate<Void>> handleOptimisticLockingFailureException(
            final ObjectOptimisticLockingFailureException e,
            final HttpServletRequest request
    ) {
        doBusinessLog(e, request);

        return toResponse(
                ErrorCode.POINT_CONCURRENT_UPDATE.getHttpStatus(),
                ErrorCode.POINT_CONCURRENT_UPDATE.getCode(),
                ErrorCode.POINT_CONCURRENT_UPDATE.getMessage()
        );
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
                ErrorCode.INTERNAL_SERVER_ERROR.getMessage(),
                runtimeDebugInfo(ex, request, HttpStatus.INTERNAL_SERVER_ERROR)
        );
    }

    private ResponseEntity<ApiResponseTemplate<Void>> toResponse(
            final HttpStatus status,
            final String code,
            final String message
    ) {
        return toResponse(status, code, message, null);
    }

    private ResponseEntity<ApiResponseTemplate<Void>> toResponse(
            final HttpStatus status,
            final String code,
            final String message,
            final ApiErrorDebugInfo debugInfo
    ) {
        final ApiResponseTemplate<Void> body = ApiResponseTemplate.<Void>error()
                .code(code)
                .message(message)
                .debug(debugInfo)
                .build(); // data = null

        return ResponseEntity.status(status).body(body);
    }

    private ApiErrorDebugInfo runtimeDebugInfo(
            final Exception exception,
            final HttpServletRequest request,
            final HttpStatus status
    ) {
        if (!includeDebugDetails || !status.is5xxServerError()) {
            return null;
        }
        return ApiErrorDebugInfo.of(exception, request.getRequestURI());
    }

    private static HttpStatus getHttpStatusOrDefault(final ErrorCode errorCode) {
        try {
            final HttpStatus s = errorCode.getHttpStatus();
            if (s == null) {
                return HttpStatus.BAD_REQUEST;
            }
            return s;
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
        if (end == s.length()) {
            return s;
        }
        return s.substring(0, end);
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
        if (primary == null || primary.isBlank()) {
            return fallback;
        }
        return primary;
    }

    private String toStackTraceLog(final Exception exception) {
        return Arrays.stream(exception.getStackTrace())
                .map(StackTraceElement::toString)
                .collect(Collectors.joining("\n"));
    }
}
