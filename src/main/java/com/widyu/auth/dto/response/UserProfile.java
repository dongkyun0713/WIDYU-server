package com.widyu.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserProfile(
        @Schema(description = "사용자 이름", example = "김민지") String name,
        @Schema(description = "전화번호", example = "010-1234-5678") String phoneNumber,
        @Schema(description = "이메일", example = "abc@abc.com") String email,
        @Schema(description = "소셜 로그인 제공자 목록", example = "[\"kakao\"]") List<String> providers
) {
    public static UserProfile of(String name, String phoneNumber, String email, List<String> providers) {
        return new UserProfile(name, phoneNumber, email, providers);
    }
}