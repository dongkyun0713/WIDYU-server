package com.widyu.global.response;

/** 400 응답에서 어떤 입력이 왜 틀렸는지 프론트가 필드 옆에 표시할 수 있게 하는 항목(LLD-0085). */
public record FieldErrorDetail(String field, String reason) {

    public static FieldErrorDetail of(final String field, final String reason) {
        return new FieldErrorDetail(field, reason);
    }
}
