package com.widyu.global.response;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ApiResponseTemplate<T> {

    private String code;
    private String message;
    private T data;

    public static <T> ApiResponseTemplate<T> ok() {
        return new ApiResponseTemplate<>();
    }

    public ApiResponseTemplate<T> code(String code) {
        this.code = code;
        return this;
    }

    public ApiResponseTemplate<T> message(String message) {
        this.message = message;
        return this;
    }

    public ApiResponseTemplate<T> data(T data) {
        this.data = data;
        return this;
    }
}
