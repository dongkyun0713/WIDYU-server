package com.widyu.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CurrentMemberResponse(
        @Schema(description = "회원 이름", example = "ghdrlfehd")
        String name,
        
        @Schema(description = "전화번호", example = "01012341234")
        String phone,
        
        @Schema(description = "이메일", example = "abc@abc.com")
        String email,
        
        @Schema(description = "연동된 소셜 로그인 제공자", example = "[\"kakao\"]")
        List<String> providers,
        
        @Schema(description = "부모 계정 보유 여부", example = "true")
        boolean hasParents
) {
    public static CurrentMemberResponse of(String name, String phone, String email, List<String> providers, boolean hasParents) {
        return new CurrentMemberResponse(name, phone, email, providers, hasParents);
    }
}