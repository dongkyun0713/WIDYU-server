package com.widyu.global.response;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.slf4j.MDC;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ApiResponseTemplate<T> {

    private final String code;
    private final String message;
    private final T data;
    private final String traceId;
    private final ApiErrorDebugInfo debug;

    public static BodyBuilder ok() {
        return new DefaultBodyBuilder();
    }

    public static BodyBuilder error() {
        return new DefaultBodyBuilder();
    }

    public interface BodyBuilder {
        BodyBuilder code(final String code);

        BodyBuilder message(final String message);

        BodyBuilder debug(final ApiErrorDebugInfo debug);

        <T> ApiResponseTemplate<T> body(final T data);

        <T> ApiResponseTemplate<T> build();
    }

    private static final class DefaultBodyBuilder implements BodyBuilder {
        private String code;
        private String message;
        private ApiErrorDebugInfo debug;

        @Override
        public BodyBuilder code(final String code) {
            this.code = code;
            return this;
        }

        @Override
        public BodyBuilder message(final String message) {
            this.message = message;
            return this;
        }

        @Override
        public BodyBuilder debug(final ApiErrorDebugInfo debug) {
            this.debug = debug;
            return this;
        }

        @Override
        public <T> ApiResponseTemplate<T> body(final T data) {
            return new ApiResponseTemplate<>(this.code, this.message, data, MDC.get("traceId"), this.debug);
        }

        @Override
        public <T> ApiResponseTemplate<T> build() {
            return new ApiResponseTemplate<>(this.code, this.message, null, MDC.get("traceId"), this.debug);
        }
    }
}
