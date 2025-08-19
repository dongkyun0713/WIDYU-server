package com.widyu.global.response;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ApiResponseTemplate<T> {

    private final String code;
    private final String message;
    private final T data;

    public static BodyBuilder ok() {
        return new DefaultBodyBuilder();
    }

    public interface BodyBuilder {
        BodyBuilder code(String code);
        BodyBuilder message(String message);
        <T> ApiResponseTemplate<T> body(T data);
        <T> ApiResponseTemplate<T> build();
    }

    private static final class DefaultBodyBuilder implements BodyBuilder {
        private String code;
        private String message;

        @Override
        public BodyBuilder code(String code) {
            this.code = code;
            return this;
        }

        @Override
        public BodyBuilder message(String message) {
            this.message = message;
            return this;
        }

        @Override
        public <T> ApiResponseTemplate<T> body(T data) {
            return new ApiResponseTemplate<>(this.code, this.message, data);
        }

        @Override
        public <T> ApiResponseTemplate<T> build() {
            return new ApiResponseTemplate<>(this.code, this.message, null);
        }
    }
}
