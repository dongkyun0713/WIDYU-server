package com.widyu.global.error;

import com.widyu.global.log.ApiRequestContext;
import com.widyu.global.log.BusinessExceptionLogEntry;
import com.widyu.auth.exception.AuthRateLimitException;
import com.widyu.global.log.ExceptionLogEntry;
import com.widyu.global.log.StackTraceLog;
import com.widyu.global.response.ApiErrorDebugInfo;
import com.widyu.global.response.ApiResponseTemplate;
import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.widyu.global.response.FieldErrorDetail;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
    private static final String INVALID_FORMAT_REASON = "요청 값의 형식이 올바르지 않습니다";
    private static final String TYPE_MISMATCH_REASON = "요청 값의 타입이 올바르지 않습니다";
    private static final String TOO_LONG_REASON = "허용 길이를 넘었습니다";

    @Value("${observability.error-response.include-debug-details:false}")
    private boolean includeDebugDetails;

    @ExceptionHandler(AuthRateLimitException.class)
    public ResponseEntity<ApiResponseTemplate<Void>> handleAuthRateLimitException(AuthRateLimitException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(exception.getRetryAfterSeconds()))
                .body(ApiResponseTemplate.<Void>error()
                        .code(ErrorCode.AUTH_RATE_LIMITED.getCode())
                        .message(ErrorCode.AUTH_RATE_LIMITED.getMessage()).build());
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponseTemplate<Void>> handleBusinessException(
            final BusinessException ex,
            final HttpServletRequest request
    ) {
        final ErrorCode errorCode = ex.getErrorCode();
        final HttpStatus status = getHttpStatusOrDefault(errorCode);
        // 응답이 5xx면 예외 종류와 상관없이 서버 오류 로그로 남겨 API 실패 알림에 잡히게 한다(LLD-0084).
        if (status.is5xxServerError()) {
            doSystemLog(ex, request);
        } else {
            doBusinessLog(ex, request);
        }

        String detail = nullSafe(ex.getMessage(), errorCode.getMessage());
        if (isAuthRequest(request)) {
            detail = errorCode.getMessage();
        }

        return toResponse(status, errorCode.getCode(), detail, runtimeDebugInfo(ex, request, status));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    protected ResponseEntity<ApiResponseTemplate<Void>> handleMethodArgumentNotValidException(
            final MethodArgumentNotValidException e
    ) {
        final List<FieldErrorDetail> errors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> FieldErrorDetail.of(error.getField(), reasonOf(error.getDefaultMessage())))
                .toList();
        log.warn("Validation error for fields {}", errors.stream().map(FieldErrorDetail::field).toList());

        return inputError(ErrorCode.INVALID_INPUT_VALUE, errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    protected ResponseEntity<ApiResponseTemplate<Void>> handleHandlerMethodValidationException(
            final HandlerMethodValidationException e
    ) {
        final List<FieldErrorDetail> errors = new ArrayList<>();
        for (ParameterValidationResult result : e.getAllValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                parameterErrors.getFieldErrors().forEach(error ->
                        errors.add(FieldErrorDetail.of(error.getField(), reasonOf(error.getDefaultMessage()))));
                continue;
            }
            final String parameter = nullSafe(result.getMethodParameter().getParameterName(), "unknown");
            result.getResolvableErrors().forEach(error ->
                    errors.add(FieldErrorDetail.of(parameter, reasonOf(error.getDefaultMessage()))));
        }
        log.warn("Validation error for parameters {}", errors.stream().map(FieldErrorDetail::field).toList());

        return inputError(ErrorCode.INVALID_INPUT_VALUE, errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    protected ResponseEntity<ApiResponseTemplate<Void>> handleHttpMessageNotReadableException(
            final HttpMessageNotReadableException e
    ) {
        final String field = jsonPathOf(e);
        log.warn("Malformed request body: field={}", field);
        if (field == null) {
            return toResponse(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST_FORMAT.getCode(),
                    "요청 본문을 읽을 수 없습니다. 형식을 확인해주세요.");
        }

        return inputError(ErrorCode.INVALID_REQUEST_FORMAT, List.of(FieldErrorDetail.of(field, INVALID_FORMAT_REASON)));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    protected ResponseEntity<ApiResponseTemplate<Void>> handleMethodArgumentTypeMismatchException(
            final MethodArgumentTypeMismatchException e
    ) {
        log.warn("Type mismatch for parameter {}", e.getName());

        return inputError(ErrorCode.INVALID_REQUEST_FORMAT, List.of(FieldErrorDetail.of(e.getName(), TYPE_MISMATCH_REASON)));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    protected ResponseEntity<ApiResponseTemplate<Void>> handleMissingServletRequestParameterException(
            final MissingServletRequestParameterException e
    ) {
        log.warn("Missing request parameter: {}", e.getParameterName());

        return inputError(ErrorCode.MISSING_REQUIRED_VALUE, List.of(FieldErrorDetail.of(e.getParameterName(), "필수 요청 파라미터가 누락되었습니다")));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    protected ResponseEntity<ApiResponseTemplate<Void>> handleMissingServletRequestPartException(
            final MissingServletRequestPartException e
    ) {
        log.warn("Missing request part: {}", e.getRequestPartName());

        return inputError(ErrorCode.MISSING_REQUIRED_VALUE, List.of(FieldErrorDetail.of(e.getRequestPartName(), "필수 요청 파트가 누락되었습니다")));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    protected ResponseEntity<ApiResponseTemplate<Void>> handleMissingRequestHeaderException(
            final MissingRequestHeaderException e
    ) {
        log.warn("Missing request header: {}", e.getHeaderName());

        return inputError(ErrorCode.MISSING_REQUIRED_VALUE, List.of(FieldErrorDetail.of(e.getHeaderName(), "필수 요청 헤더가 누락되었습니다")));
    }

    /**
     * DB 무결성 위반을 원인별로 응답한다(LLD-0085).
     * 길이 초과는 클라이언트 입력이라 400이지만 요청 검증이 빠졌다는 신호라 서버 오류 로그로 남겨 알림을 유지한다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponseTemplate<Void>> handleDataIntegrityViolationException(
            final DataIntegrityViolationException e,
            final HttpServletRequest request
    ) {
        final DataIntegrityViolationKind kind = DataIntegrityViolationKind.classify(e);
        if (kind.type() == DataIntegrityViolationKind.Type.TOO_LONG) {
            doSystemLog(e, request);
            if (kind.field() == null) {
                return toResponse(HttpStatus.BAD_REQUEST, ErrorCode.INPUT_TOO_LONG.getCode(), ErrorCode.INPUT_TOO_LONG.getMessage());
            }
            return inputError(ErrorCode.INPUT_TOO_LONG, List.of(FieldErrorDetail.of(kind.field(), TOO_LONG_REASON)));
        }
        if (kind.type() == DataIntegrityViolationKind.Type.DUPLICATE) {
            doBusinessLog(e, request);
            return toResponse(ErrorCode.DUPLICATE_RESOURCE.getHttpStatus(), ErrorCode.DUPLICATE_RESOURCE.getCode(),
                    ErrorCode.DUPLICATE_RESOURCE.getMessage());
        }
        return serverError(ErrorCode.DATA_INTEGRITY_ERROR, e, request);
    }

    /** 락 대기·쿼리 시간 초과처럼 잠시 뒤 다시 하면 될 수 있는 실패. */
    @ExceptionHandler({PessimisticLockingFailureException.class, QueryTimeoutException.class,
            AsyncRequestTimeoutException.class})
    public ResponseEntity<ApiResponseTemplate<Void>> handleTemporarilyBusy(
            final Exception e,
            final HttpServletRequest request
    ) {
        return serverError(ErrorCode.TEMPORARILY_BUSY, e, request);
    }

    /** DB·Redis 연결 실패처럼 의존 시스템을 쓸 수 없는 실패. */
    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    public ResponseEntity<ApiResponseTemplate<Void>> handleDependencyUnavailable(
            final Exception e,
            final HttpServletRequest request
    ) {
        return serverError(ErrorCode.DEPENDENCY_UNAVAILABLE, e, request);
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

    /**
     * 클라이언트가 응답을 받기 전에 연결을 끊어 응답 쓰기가 실패한 경우(Broken pipe 등).
     * 서버 오류가 아니고 응답을 보낼 곳도 없으므로 시스템 오류 로그·알림으로 남기지 않는다(#938).
     * null을 반환하면 Spring이 응답 처리를 끝난 것으로 보고 아무것도 쓰지 않는다.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<Void> handleClientDisconnected(
            final AsyncRequestNotUsableException e,
            final HttpServletRequest request
    ) {
        log.debug("클라이언트가 응답 전에 연결을 끊음: uri={}", request.getRequestURI());
        return null;
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

    private ResponseEntity<ApiResponseTemplate<Void>> serverError(
            final ErrorCode errorCode,
            final Exception exception,
            final HttpServletRequest request
    ) {
        doSystemLog(exception, request);
        return toResponse(errorCode.getHttpStatus(), errorCode.getCode(), errorCode.getMessage(),
                runtimeDebugInfo(exception, request, errorCode.getHttpStatus()));
    }

    /** 400 응답. message는 기존 클라이언트와 맞추려고 첫 번째 사유를 "사유 (필드)"로 담고, errors에 전체 목록을 담는다. */
    private ResponseEntity<ApiResponseTemplate<Void>> inputError(
            final ErrorCode errorCode,
            final List<FieldErrorDetail> errors
    ) {
        String message = errorCode.getMessage();
        if (!errors.isEmpty()) {
            final FieldErrorDetail first = errors.get(0);
            message = String.format("%s (%s)", trimTrailingPeriod(first.reason()), first.field());
        }
        final ApiResponseTemplate<Void> body = ApiResponseTemplate.<Void>error()
                .code(errorCode.getCode())
                .message(message)
                .errors(errors)
                .build();
        return ResponseEntity.status(errorCode.getHttpStatus()).body(body);
    }

    private static String reasonOf(final String defaultMessage) {
        return nullSafe(defaultMessage, "요청 값이 유효하지 않습니다");
    }

    /** JSON 역직렬화 실패 위치를 "a.b[0].c" 형태로 꺼낸다. 문법 오류처럼 위치가 없으면 null. */
    private static String jsonPathOf(final HttpMessageNotReadableException exception) {
        if (!(exception.getCause() instanceof MismatchedInputException mismatch) || mismatch.getPath().isEmpty()) {
            return null;
        }
        final StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : mismatch.getPath()) {
            if (reference.getFieldName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getFieldName());
                continue;
            }
            path.append('[').append(reference.getIndex()).append(']');
        }
        return path.toString();
    }

    private ApiErrorDebugInfo runtimeDebugInfo(
            final Exception exception,
            final HttpServletRequest request,
            final HttpStatus status
    ) {
        if (isAuthRequest(request) || !includeDebugDetails || !status.is5xxServerError()) {
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
        String message = runtimeException.getMessage();
        if (isAuthRequest(request)) {
            message = "Authentication request rejected";
        }
        final BusinessExceptionLogEntry entry = BusinessExceptionLogEntry.builder()
                .timestamp(OffsetDateTime.now().toString())
                .exceptionType(runtimeException.getClass().getName())
                .message(message)
                .requestUri(request.getRequestURI())
                .build();
        final Marker marker = MarkerFactory.getMarker(BUSINESS_LOG_MARKER);
        log.warn(marker, entry.toLogString());
    }

    private void doSystemLog(final Exception exception, final HttpServletRequest request) {
        String message = exception.getMessage();
        if (isAuthRequest(request)) {
            message = "Authentication request failed";
        }
        final ExceptionLogEntry entry = ExceptionLogEntry.builder()
                .timestamp(OffsetDateTime.now().toString())
                .api(ApiRequestContext.apiName(request))
                .httpMethod(request.getMethod())
                .path(ApiRequestContext.pathPattern(request))
                .actor(ApiRequestContext.actor())
                .target(ApiRequestContext.target(request))
                .exceptionType(exception.getClass().getName())
                .message(message)
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
        return StackTraceLog.oneLine(exception);
    }

    private boolean isAuthRequest(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/api/v1/auth/");
    }
}
